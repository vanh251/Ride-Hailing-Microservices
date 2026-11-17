package com.example.apigateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Builds tokens shaped like identity-service's (HS256, sub = userId, claim role) for tests.
 */
public final class TestTokens {

    /** Tests pass this value as jwt.secret. */
    public static final String TEST_SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQta2V5LWZvci1pZGVudGl0eS1zZXJ2aWNlLXRlc3Rz";
    public static final String OTHER_SECRET = Base64.getEncoder().encodeToString(
            "another-secret-key-that-did-not-sign-the-token-xyz".getBytes(StandardCharsets.UTF_8));

    private TestTokens() {
    }

    public static String token(UUID userId, String role) {
        return token(TEST_SECRET, userId.toString(), role, Instant.now().plusSeconds(3600));
    }

    public static String token(String secret, String subject, String role, Instant expiresAt) {
        var builder = Jwts.builder()
                .setSubject(subject)
                .setIssuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .setExpiration(Date.from(expiresAt));
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)), SignatureAlgorithm.HS256).compact();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
