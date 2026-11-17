package com.example.driverservice.config;

import com.example.driverservice.service.JwtService;
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
 * Access rules for driver-service (docs/api.md, section 3). Ownership ("own resource") is checked in the service
 * by comparing the path id with the token's user id, since a driver's id is its user id.
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
                .requestMatchers(
                    "/actuator/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    // Service-to-service; guarded by InternalApiKeyInterceptor instead of a JWT
                    "/internal/**"
                ).permitAll()
                // matching-service calls this without a token (it has no service credential yet)
                .requestMatchers(HttpMethod.GET, "/api/drivers/available").permitAll()
                // Called by matching-service only; guarded by InternalApiKeyInterceptor instead of a JWT
                .requestMatchers(HttpMethod.POST, "/api/drivers/*/reserve").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/drivers/*/reserve").permitAll()
                // Every method, not only GET: Spring MVC answers HEAD with the GET handler, so a GET-only rule
                // would let any role run the search with HEAD
                .requestMatchers("/api/drivers/nearby").hasRole("PASSENGER")
                .requestMatchers(HttpMethod.PATCH, "/api/drivers/*/status", "/api/drivers/*/location").hasRole("DRIVER")
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
