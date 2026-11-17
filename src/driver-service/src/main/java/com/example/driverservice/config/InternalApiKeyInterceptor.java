package com.example.driverservice.config;

import com.example.driverservice.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the service-to-service APIs: /internal/** (identity-service) and /api/drivers/{id}/reserve (matching-service).
 * The API Gateway does not route these paths, but port 8082 is published to the host, so callers must also present
 * the shared key from INTERNAL_API_KEY.
 * Registered as an MVC interceptor so it matches paths exactly the way handler mapping does.
 */
@Slf4j
@Component
public class InternalApiKeyInterceptor implements HandlerInterceptor {

    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final byte[] expectedKey;

    public InternalApiKeyInterceptor(@Value("${internal.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("internal.api-key (INTERNAL_API_KEY) must be set");
        }
        this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String providedKey = request.getHeader(INTERNAL_API_KEY_HEADER);
        // Constant-time comparison so the response time does not reveal how much of the key matched
        if (providedKey == null || !MessageDigest.isEqual(expectedKey, providedKey.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected internal call to {} without a valid internal API key", request.getRequestURI());
            throw new BusinessException("Missing or invalid internal API key",
                    HttpStatus.UNAUTHORIZED, "INVALID_INTERNAL_API_KEY");
        }
        return true;
    }
}
