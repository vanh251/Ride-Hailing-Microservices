package com.example.apigateway.config;

import com.example.apigateway.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String MISSING_TOKEN = "MISSING_TOKEN";

    private final SecurityErrorWriter errorWriter;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object jwtError = request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE);
        String code = jwtError != null ? jwtError.toString() : MISSING_TOKEN;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, code, messageFor(code));
    }

    private String messageFor(String code) {
        return switch (code) {
            case JwtAuthenticationFilter.TOKEN_EXPIRED -> "JWT has expired, please log in again";
            case JwtAuthenticationFilter.INVALID_TOKEN -> "JWT is invalid";
            default -> "Authentication required: send 'Authorization: Bearer <token>'";
        };
    }
}
