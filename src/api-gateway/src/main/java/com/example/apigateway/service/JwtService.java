package com.example.apigateway.service;

import com.example.apigateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Verifies HS256 access tokens issued by identity-service (sub = userId, claim role = UserRole).
 * The gateway never issues tokens.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

    public static final String ROLE_CLAIM = "role";

    private final JwtParser parser;

    public JwtService(JwtProperties properties) {
        // Fails fast at startup if the secret is not Base64 or shorter than 256 bits
        this.parser = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret())))
                .build();
    }

    /**
     * Verifies the signature and expiry and returns the claims.
     * Throws {@link io.jsonwebtoken.ExpiredJwtException} for an expired token and another
     * {@link io.jsonwebtoken.JwtException} (or IllegalArgumentException for a blank token) for any other invalid token.
     */
    public Claims parseToken(String token) {
        return parser.parseClaimsJws(token).getBody();
    }
}
