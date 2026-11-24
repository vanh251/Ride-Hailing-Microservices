package com.example.paymentservice;

import com.example.paymentservice.payos.CheckoutIdentity;
import com.example.paymentservice.exception.BusinessException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutIdentityTest {
    private static final byte[] KEY="01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final CheckoutIdentity identity=new CheckoutIdentity(Base64.getEncoder().encodeToString(KEY));
    @Test void verifiesSignedIdentityInsteadOfTrustingCallerHeaders() {
        UUID user=UUID.randomUUID();
        String token=token(user,"PASSENGER",new Date(System.currentTimeMillis()+60000),KEY);
        assertEquals(user,identity.verify("Bearer "+token).id());
        assertThrows(BusinessException.class,()->identity.verify(null));
        assertThrows(BusinessException.class,()->identity.verify("Bearer "+token(user,"PASSENGER",new Date(1),KEY)));
        assertThrows(BusinessException.class,()->identity.verify("Bearer "+token(user,"ADMIN",new Date(System.currentTimeMillis()+60000),KEY)));
        assertThrows(BusinessException.class,()->identity.verify("Bearer "+token(user,"PASSENGER",new Date(System.currentTimeMillis()+60000),"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".getBytes())));
        assertThrows(BusinessException.class,()->identity.verify("Bearer "+Jwts.builder().setSubject(user.toString()).claim("role","PASSENGER").compact()));
    }
    private static String token(UUID user,String role,Date expires,byte[] key) {
        return Jwts.builder().setSubject(user.toString()).claim("role",role).setExpiration(expires)
                .signWith(Keys.hmacShaKeyFor(key),SignatureAlgorithm.HS256).compact();
    }
}
