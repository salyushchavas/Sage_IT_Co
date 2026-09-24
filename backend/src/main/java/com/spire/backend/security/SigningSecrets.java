package com.spire.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * The boot check for JWT_SECRET, the key that signs every login token (LMS
 * users, the agreement console and consultants). Whoever knows it can mint a
 * login for any account, so the app refuses to start when it is missing,
 * shorter than 32 bytes, or a value that has been published.
 *
 * <p>Published values are recognised by their SHA-256 alone: the values are
 * never in the code, and the error names the variable, never the value.
 */
public final class SigningSecrets {

    public static final int MIN_BYTES = 32;

    /**
     * SHA-256 of every signing secret ever committed to this public repository
     * or to the sister Spireinfotech one (a value copied across would be just
     * as public).
     */
    public static final Set<String> PUBLISHED_SHA256 = Set.of(
            // jwt.secret's old default in application.properties (the only one in this repo's history)
            "649ee5c91d6eefca164dc5eb13c04003137d2e7bdd2ee85184e1f5c1a6a58b11",
            // Spireinfotech: mail.jwt-secret's old default, the MAIL_JWT_SECRET samples in its
            // themes/*.env, and a jwt.secret once committed in themes/spire.env
            "065d54c4a11d83d19b9dff3ca1251fdd93433591925292898ff895e42c9bb57f",
            "f008c591171c8e4453caf1c12d8d608dc39a48aa826a2303696f99966168cdeb",
            "e50a8811cb3c50157891dde87a750d05d5d681122f54c9b2df6e406778b875f9",
            "14f9a455b5a595c3c47b799f2ab40120d4658fbe0f82f29b14b85409b617f41d");

    private SigningSecrets() {}

    /**
     * Throws when {@code secret} mustn't sign tokens. The message names
     * {@code envVar} and says how to make a new value; it never includes a value.
     */
    public static void check(String envVar, String secret, Set<String> publishedSha256) {
        if (secret == null || secret.isBlank()) {
            throw refused(envVar + " is not set");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
            throw refused(envVar + " is shorter than " + MIN_BYTES + " bytes");
        }
        if (publishedSha256.contains(sha256Hex(secret))) {
            throw refused(envVar + " is a value that has been published in a code repository");
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static IllegalStateException refused(String problem) {
        return new IllegalStateException(problem + ": set a fresh random value (e.g. openssl rand -base64 48)");
    }
}
