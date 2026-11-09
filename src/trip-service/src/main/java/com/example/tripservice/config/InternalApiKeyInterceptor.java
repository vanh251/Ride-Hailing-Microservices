package com.example.tripservice.config;

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
            String path = request.getRequestURI();
            boolean allowed = "GET".equals(request.getMethod()) && path.matches("/api/trips/[0-9a-fA-F-]{36}")
                    || "POST".equals(request.getMethod()) && path.matches("/api/trips/[0-9a-fA-F-]{36}/cancel");
            if (!allowed)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal operation not allowed");
            request.setAttribute("internalCall", true);
            return true;
        }
        String identity = request.getHeader("X-User-Id");
        try {
            UUID.fromString(identity);
        }
        catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated identity required");
        }
        if ("POST".equals(request.getMethod())) {
            String expected = request.getRequestURI().endsWith("/accept") || request.getRequestURI().endsWith("/start")
                    || request.getRequestURI().endsWith("/complete") ? "DRIVER" : "PASSENGER";
            if (!expected.equals(request.getHeader("X-User-Role")))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role not allowed");
        }
        return true;
    }

}
