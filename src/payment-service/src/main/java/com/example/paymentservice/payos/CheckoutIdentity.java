package com.example.paymentservice.payos;

import com.example.paymentservice.exception.BusinessException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class CheckoutIdentity {
    public record Identity(UUID id, String role) {}
    private final JwtParser parser;
    public CheckoutIdentity(@Value("${jwt.secret:dGhpc19pc19hX3Zlcnlfc2VjdXJlX2tleV9mb3JfaHMyNTZfamF2YV9zcHJpbmdfYm9vdF9kczAzX3JpZGVfaGFpbGluZw==}") String secret) {
        parser=Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))).build();
    }
    public Identity verify(String authorization) {
        try {
            if (authorization==null || !authorization.startsWith("Bearer ")) throw new IllegalArgumentException();
            var jwt=parser.parseClaimsJws(authorization.substring(7));
            if (!"HS256".equals(jwt.getHeader().getAlgorithm()) || jwt.getBody().getExpiration()==null) throw new IllegalArgumentException();
            var identity=new Identity(UUID.fromString(jwt.getBody().getSubject()),jwt.getBody().get("role",String.class));
            if (!"PASSENGER".equals(identity.role()) && !"DRIVER".equals(identity.role())) throw new IllegalArgumentException();
            return identity;
        } catch (Exception e) { throw new BusinessException("Valid signed passenger/driver bearer token required",HttpStatus.UNAUTHORIZED,"UNAUTHORIZED"); }
    }
}
