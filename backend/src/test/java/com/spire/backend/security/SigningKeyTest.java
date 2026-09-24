package com.spire.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checklist 0.3: the app refuses a missing, short or published signing key,
 * and /api/auth/refresh only accepts real refresh tokens.
 * (Published keys are tested with an injected hash, never the real values.)
 */
class SigningKeyTest {

    private static final String GOOD = "test-only-signing-key-0123456789-abcdefghijklmnop";

    @Test
    void missingShortOrPublishedKeysAreRefusedWithoutEchoingThem() {
        assertThrows(IllegalStateException.class, () -> SigningSecrets.check("JWT_SECRET", null, Set.of()));
        assertThrows(IllegalStateException.class, () -> SigningSecrets.check("JWT_SECRET", "  ", Set.of()));
        IllegalStateException shortKey = assertThrows(IllegalStateException.class,
                () -> SigningSecrets.check("JWT_SECRET", "too-short-key", Set.of()));
        assertFalse(shortKey.getMessage().contains("too-short-key"));

        Set<String> published = Set.of(SigningSecrets.sha256Hex(GOOD));
        IllegalStateException leaked = assertThrows(IllegalStateException.class,
                () -> SigningSecrets.check("JWT_SECRET", GOOD, published));
        assertTrue(leaked.getMessage().contains("JWT_SECRET"));
        assertFalse(leaked.getMessage().contains(GOOD));

        assertDoesNotThrow(() -> SigningSecrets.check("JWT_SECRET", GOOD, SigningSecrets.PUBLISHED_SHA256));
    }

    @Test
    void theServiceItselfRefusesToStartWithoutAKey() {
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secretKey", "");
        assertThrows(IllegalStateException.class, jwt::validateSigningSecret);
    }

    @Test
    void onlyRealRefreshTokensCountAsRefreshTokens() {
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secretKey", GOOD);
        ReflectionTestUtils.setField(jwt, "accessTokenExpiration", 900_000L);
        ReflectionTestUtils.setField(jwt, "refreshTokenExpiration", 604_800_000L);
        jwt.validateSigningSecret();

        assertTrue(jwt.isRefreshToken(jwt.generateRefreshToken(7L)));
        assertFalse(jwt.isRefreshToken(jwt.generateAccessToken(7L, "SYSTEM_ADMIN")), "an access token");
        assertFalse(jwt.isRefreshToken(jwt.generateConsultantToken("c@x.com")), "a consultant token");
        assertFalse(jwt.isRefreshToken(jwt.generateAgreementUserToken("1", "e@x.com", "ERM", "E", "T")), "a console token");
        assertFalse(jwt.isRefreshToken("not-a-token"));

        JwtService otherKey = new JwtService();
        ReflectionTestUtils.setField(otherKey, "secretKey", GOOD + "-other");
        ReflectionTestUtils.setField(otherKey, "refreshTokenExpiration", 604_800_000L);
        assertFalse(jwt.isRefreshToken(otherKey.generateRefreshToken(7L)), "signed with another key");
    }
}
