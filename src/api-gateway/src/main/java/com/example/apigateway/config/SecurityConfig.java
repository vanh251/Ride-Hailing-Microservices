package com.example.apigateway.config;

import com.example.apigateway.filter.JwtAuthenticationFilter;
import com.example.apigateway.service.JwtService;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Access rules at the gateway (docs/api.md §1 and the "Auth required" line of each endpoint).
 * The gateway checks the token and the role; "own resource" checks stay in the backend services.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // A backend that is down ends in an error dispatch to /error; without this it would be answered
                // with 401 instead of the real 5xx, because the JWT filter does not run again on that dispatch
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/demo/**").permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/api/auth/register/passenger",
                    "/api/auth/register/driver",
                    "/api/auth/login"
                ).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/*/pay").hasRole("PASSENGER")
                // Public for now: see GET /api/drivers/available in docs/api.md
                .requestMatchers(HttpMethod.GET, "/api/drivers/available").permitAll()
                // Service-to-service paths have no route, so letting them through ends in the gateway's 404 (docs/api.md §10)
                .requestMatchers(
                    "/internal/**",
                    "/api/drivers/*/reserve",
                    "/api/matching/*/rematch",
                    "/api/pricing/final",
                    "/api/payments/*/charge",
                    "/api/payments/*/refund"
                ).permitAll()
                // Every method, not only GET: a backend answers HEAD with its GET handler, so a GET-only rule
                // would let any role run the search with HEAD
                .requestMatchers("/api/drivers/nearby").hasRole("PASSENGER")
                // Only .../match is routed ("internal or admin"); the whole prefix is ADMIN so that another method
                // or spelling of the path cannot reach matching-service with a PASSENGER or DRIVER token
                .requestMatchers("/api/matching/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/drivers/*/status", "/api/drivers/*/location").hasRole("DRIVER")
                .requestMatchers(HttpMethod.POST, "/api/trips", "/api/trips/*/cancel").hasRole("PASSENGER")
                .requestMatchers(HttpMethod.POST,
                    "/api/trips/*/accept",
                    "/api/trips/*/start",
                    "/api/trips/*/complete"
                ).hasRole("DRIVER")
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            // Created here rather than as a @Component so Spring Boot does not also register it as a plain servlet filter
            .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
