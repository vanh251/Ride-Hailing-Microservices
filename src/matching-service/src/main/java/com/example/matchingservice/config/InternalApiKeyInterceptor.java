package com.example.matchingservice.config;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
public class InternalApiKeyInterceptor implements HandlerInterceptor {

    private final String key;

    public InternalApiKeyInterceptor(@Value("${internal.api-key:}") String key) {
        this.key = key;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String supplied = request.getHeader("X-Internal-Api-Key");
        if (supplied != null) {
            if (key.isBlank() || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_INTERNAL_API_KEY");

            request.setAttribute("internalCall", true);
            return true;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_INTERNAL_API_KEY");
    }

}
