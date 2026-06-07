package com.spire.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

import java.util.function.Function;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    public String generateAccessToken(Long userId, String role) {
        return buildToken(Map.of("role", role), String.valueOf(userId), accessTokenExpiration);
    }

    public String generateRefreshToken(Long userId) {
        return buildToken(Map.of(), String.valueOf(userId), refreshTokenExpiration);
    }

    /**
     * Agreement-ERM console: 8h session token issued by
     * {@code /api/agreement-erm/login}. Subject is the operator email;
     * {@code purpose=agreement_erm} keeps the regular {@link
     * JwtAuthFilter} from trying to parse the subject as a numeric user
     * id and routes the token to {@link AgreementErmAuthFilter}.
     *
     * <p>Multi-user phase: the token now also carries the authenticated
     * agreement user's {@code userId}, {@code role} (SUPER_ADMIN | ERM),
     * {@code fullName} and {@code title} so the filter can expose
     * identity downstream without a DB round-trip. Subject stays the
     * email (unchanged) for backward compatibility.
     */
    public String generateAgreementUserToken(
            String userId, String email, String role, String fullName, String title) {
        long eightHoursMs = 8L * 60L * 60L * 1000L;
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("purpose", "agreement_erm");
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("email", email);
        claims.put("fullName", fullName);
        claims.put("title", title);
        return buildToken(claims, email, eightHoursMs);
    }

    /** UUID of the agreement user; null on legacy pre-multi-user tokens. */
    public String extractAgreementUserId(String token) {
        return extractClaim(token, claims -> claims.get("userId", String.class));
    }

    /** "SUPER_ADMIN" | "ERM"; null on legacy pre-multi-user tokens. */
    public String extractAgreementRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    public String extractAgreementFullName(String token) {
        return extractClaim(token, claims -> claims.get("fullName", String.class));
    }

    public String extractAgreementTitle(String token) {
        return extractClaim(token, claims -> claims.get("title", String.class));
    }

    public String extractSubject(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractPurpose(String token) {
        return extractClaim(token, claims -> claims.get("purpose", String.class));
    }

    public Long extractUserId(String token) {
        return Long.parseLong(extractClaim(token, Claims::getSubject));
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    public boolean isTokenValid(String token) {
        try {
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(secretKey.getBytes()));
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
