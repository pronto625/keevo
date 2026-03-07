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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SecurityConfig — Spring Security 6.x configuration (REST / stateless).
 *
 * <p>
 * <b>IMPORTANT — Spring Security vs Spring Cloud Security:</b><br>
 * This project uses {@code spring-boot-starter-security} (Spring Security 6.x),
 * which is the correct choice for a standalone Spring Boot REST API.
 *
 * <p>Configuration principles:
 * <ul>
 * <li>STATELESS — no HTTP sessions, no JSESSIONID cookie</li>
 * <li>formLogin DISABLED — prevents Spring's HTML /login redirect for REST clients</li>
 * <li>httpBasic DISABLED — no Basic-auth header prompts</li>
 * <li>401 JSON response for anonymous requests (not 302 redirect)</li>
 * <li>403 JSON response for authenticated but unauthorized requests</li>
 * <li>BCryptPasswordEncoder at cost 12 (non-negotiable)</li>
 * <li>JwtAuthFilter processes JWT tokens before Spring's auth filter</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Public paths that bypass authentication entirely. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/select-tenant",  // Story 1.7 — AC7: step 2 of two-step login is public
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

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
                        .anyRequest().authenticated())

                // ── JWT filter: runs before Spring's authentication filter ────
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
