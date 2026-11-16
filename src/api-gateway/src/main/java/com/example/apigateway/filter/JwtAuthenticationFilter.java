package com.example.apigateway.filter;

import com.example.apigateway.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String JWT_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String INVALID_TOKEN = "INVALID_TOKEN";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final Set<String> KNOWN_ROLES = Set.of("PASSENGER", "DRIVER", "ADMIN");

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID userId = authenticate(request);
        filterChain.doFilter(new UserIdRequestWrapper(request, userId), response);
    }

    private UUID authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }

        try {
            Claims claims = jwtService.parseToken(header.substring(BEARER_PREFIX.length()).trim());
            UsernamePasswordAuthenticationToken authentication = toAuthentication(claims);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            return (UUID) authentication.getPrincipal();
        } catch (ExpiredJwtException ex) {
            log.warn("Rejected expired JWT on path {}", request.getRequestURI());
            request.setAttribute(JWT_ERROR_ATTRIBUTE, TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Rejected invalid JWT on path {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
            request.setAttribute(JWT_ERROR_ATTRIBUTE, INVALID_TOKEN);
        }
        return null;
    }

    private UsernamePasswordAuthenticationToken toAuthentication(Claims claims) {
        String subject = claims.getSubject();
        String role = claims.get(JwtService.ROLE_CLAIM, String.class);
        if (subject == null || role == null || !KNOWN_ROLES.contains(role)) {
            throw new MalformedJwtException("JWT is missing the sub claim or has an unknown role");
        }
        UUID userId = UUID.fromString(subject);
        return UsernamePasswordAuthenticationToken.authenticated(userId, null,
                List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role)));
    }

    private static final class UserIdRequestWrapper extends HttpServletRequestWrapper {

        private final String userId;

        private UserIdRequestWrapper(HttpServletRequest request, UUID userId) {
            super(request);
            this.userId = userId != null ? userId.toString() : null;
        }

        @Override
        public String getHeader(String name) {
            if (USER_ID_HEADER.equalsIgnoreCase(name)) {
                return userId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (USER_ID_HEADER.equalsIgnoreCase(name)) {
                return userId != null ? Collections.enumeration(List.of(userId)) : Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(USER_ID_HEADER::equalsIgnoreCase);
            if (userId != null) {
                names.add(USER_ID_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
