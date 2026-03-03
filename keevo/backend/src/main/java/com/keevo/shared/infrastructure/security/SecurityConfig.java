package com.keevo.shared.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * SecurityConfig — Spring Security 6.x configuration (REST / stateless).
 *
 * <p>
 * <b>IMPORTANT — Spring Security vs Spring Cloud Security:</b><br>
 * This project uses {@code spring-boot-starter-security} (Spring Security 6.x),
 * which is the
 * correct choice for a standalone Spring Boot REST API. Spring Cloud Security
 * is a separate
 * module designed for OAuth2 SSO flows in microservice ecosystems and is NOT
 * needed here.
 *
 * <p>
 * Configuration principles:
 * <ul>
 * <li>STATELESS — no HTTP sessions, no JSESSIONID cookie</li>
 * <li>formLogin DISABLED — prevents Spring's HTML /login redirect for REST
 * clients</li>
 * <li>httpBasic DISABLED — no Basic-auth header prompts</li>
 * <li>401 JSON response for anonymous requests (not 302 redirect)</li>
 * <li>403 JSON response for authenticated but unauthorized requests</li>
 * <li>BCryptPasswordEncoder at cost 12 (non-negotiable)</li>
 * <li>JWT filter added in Story 1.3</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Public paths that bypass authentication entirely. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/api/v1/auth/**",
            "/v3/api-docs", // Springdoc OpenAPI JSON
            "/v3/api-docs/**", // Springdoc OpenAPI JSON
            "/swagger-ui/**", // Swagger UI static assets
            "/swagger-ui.html" // Swagger UI entry point
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // ── CSRF: disabled for stateless REST API ──────────────────────
                .csrf(csrf -> csrf.disable())

                // ── Sessions: none — JWT is stateless ────────────────────────
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── Disable HTML form login (avoids /login redirect for REST) ─
                .formLogin(form -> form.disable())

                // ── Disable HTTP Basic (avoids WWW-Authenticate prompts) ──────
                .httpBasic(basic -> basic.disable())

                // ── Return 401/403 as JSON status, not HTML redirect ──────────
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(
                                (req, res, e) -> res.sendError(HttpStatus.FORBIDDEN.value(), "Access Denied")))

                // ── Authorization rules ────────────────────────────────────────
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.FORWARD,
                                jakarta.servlet.DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated());

        // TODO (Story 1.3): Add JwtAuthFilter before
        // UsernamePasswordAuthenticationFilter
        return http.build();
    }

    /**
     * BCrypt password encoder at cost factor 12.
     * <b>NON-NEGOTIABLE</b> — NEVER lower the cost factor below 12.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
