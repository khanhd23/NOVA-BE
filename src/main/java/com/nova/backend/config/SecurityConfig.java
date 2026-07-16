package com.nova.backend.config;

import com.nova.backend.auth.AuthService;
import com.nova.backend.common.ApiError;
import com.nova.backend.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokenFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/api/v1/health", "/api/v1/auth/**", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/commerce/catalog", "/api/v1/commerce/providers").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/commerce/webhooks/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public TokenAuthenticationFilter tokenAuthenticationFilter(AuthService authService) {
        return new TokenAuthenticationFilter(authService);
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError error = new ApiError(
                    "UNAUTHORIZED",
                    "Authentication required",
                    request.getRequestURI(),
                    Map.of(),
                    Instant.now()
            );
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(response.getOutputStream(), ApiResponse.fail(error));
        };
    }

    public static final class TokenAuthenticationFilter extends OncePerRequestFilter {

        private final AuthService authService;

        public TokenAuthenticationFilter(AuthService authService) {
            this.authService = authService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            String path = request.getRequestURI();
            if (path.startsWith("/api/v1/auth")
                    || path.startsWith("/api/v1/commerce/webhooks")
                    || path.startsWith("/actuator")
                    || path.equals("/api/v1/health")
                    || path.startsWith("/uploads/")) {
                filterChain.doFilter(request, response);
                return;
            }

            String token = extractToken(request);
            if (token != null) {
                var principal = authService.resolvePrincipal(token);
                if (principal != null) {
                    Authentication authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                            principal,
                            token,
                            principal.roles().stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                    .collect(Collectors.toList())
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }

            filterChain.doFilter(request, response);
        }

        private String extractToken(HttpServletRequest request) {
            String token = extractBearerToken(request.getHeader("Authorization"));
            if (token != null) {
                return token;
            }
            String headerToken = request.getHeader("X-Auth-Token");
            if (headerToken != null && !headerToken.isBlank()) {
                return headerToken.trim();
            }
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                return queryToken.trim();
            }
            return null;
        }

        private String extractBearerToken(String header) {
            if (header == null || !header.startsWith("Bearer ")) {
                return null;
            }
            return header.substring("Bearer ".length()).trim();
        }
    }
}
