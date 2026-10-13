package com.keevo.shared.infrastructure.security;

import com.keevo.identity.auth.domain.model.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
@EnableMethodSecurity
public class SecurityConfig {

    /** Public paths that bypass authentication entirely. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/select-tenant",  // Story 1.7 — AC7: step 2 of two-step login is public
            "/api/v1/auth/forgot-password", // Story 14.12 — public password reset request
            "/api/v1/auth/reset-password",   // Story 14.12 — public password reset
            "/api/v1/exports/download",      // v1s-18-1 — signed export link (HMAC token, no JWT)
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Env var holding the comma-separated browser origins allowed by CORS (Story 11.4 AC5). */
    static final String DASHBOARD_ORIGIN_ENV = "DASHBOARD_ORIGIN";

    /** Local Next.js dashboard — allowed only outside the prod/staging profiles (AC6). */
    static final String LOCAL_DASHBOARD_ORIGIN = "http://localhost:3000";

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper, Environment environment) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // ── CSRF: disabled for stateless REST API ──────────────────────
                .csrf(csrf -> csrf.disable())

                // ── CORS: configured to allow dashboard origin ─────────────────
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // ── Sessions: none — JWT is stateless ────────────────────────
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // ── Disable HTML form login (avoids /login redirect for REST) ─
                .formLogin(form -> form.disable())

                // ── Disable HTTP Basic (avoids WWW-Authenticate prompts) ──────
                .httpBasic(basic -> basic.disable())

                // ── Return 401/403 as JSON status, not HTML redirect ──────────
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // Story 12.12: JSON body (ApiResponseWrapper, same shape as
                        // GlobalExceptionHandler.handleAccessDenied) instead of servlet HTML.
                        .accessDeniedHandler((req, res, e) -> {
                            if (res.isCommitted()) {
                                return; // body already sent — nothing can be written
                            }
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(res.getWriter(), ApiResponseWrapper.error(
                                    "Vous n'avez pas les droits nécessaires",
                                    "FORBIDDEN", "FORBIDDEN", null));
                        }))

                // ── Authorization rules ────────────────────────────────────────
                // S3 defense-in-depth: /api/v1/admin/** requires SUPER_ADMIN at the
                // filter-chain level so any future admin endpoint is protected by default
                // even if it forgets the per-method requireSuperAdmin() guard (AC2).
                // Controllers still keep requireSuperAdmin() as a second layer.
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.FORWARD,
                                jakarta.servlet.DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(Role.SUPER_ADMIN.name())
                        .anyRequest().authenticated())

                // ── JWT filter: runs before Spring's authentication filter ────
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS configuration — explicit allow-list, never a wildcard (Story 11.4 AC5/AC6). This bean is
     * the single CORS source of the application (no MVC-level CORS mapping).
     *
     * <ul>
     *   <li>Allowed origins: the comma-separated {@code DASHBOARD_ORIGIN} values that are well-formed
     *       {@code http(s)://host[:port]} origins (see {@link #parseOrigins(String)}).</li>
     *   <li>{@code http://localhost:3000} is added only for local profiles: no active profile
     *       (default) or one of {@code dev}, {@code local}, {@code test}. Every other profile —
     *       including a misspelled one — is hardened (fails closed).</li>
     * </ul>
     *
     * <p>Credentials are NOT allowed: the API is stateless bearer-JWT (no cookies). The mobile app (no
     * {@code Origin} header) and the dashboard's server-side proxy are not subject to CORS. A missing
     * {@code DASHBOARD_ORIGIN} is reported once at boot by {@code ProdSecretsValidator}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** Resolves the CORS allow-list from {@code DASHBOARD_ORIGIN} and the active profiles. */
    List<String> allowedOrigins() {
        List<String> origins = new ArrayList<>(parseOrigins(environment.getProperty(DASHBOARD_ORIGIN_ENV)));
        if (isLocalProfile() && !origins.contains(LOCAL_DASHBOARD_ORIGIN)) {
            origins.add(LOCAL_DASHBOARD_ORIGIN);
        }
        return List.copyOf(origins);
    }

    /** Local dev only: no active profile, or one of dev / local / test. */
    private boolean isLocalProfile() {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 || environment.acceptsProfiles(Profiles.of("dev", "local", "test"));
    }

    /** {@code http(s)://host[:port]} — no path, no query, no wildcard, no placeholder. */
    private static final java.util.regex.Pattern ORIGIN =
            java.util.regex.Pattern.compile("^https?://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::\\d{1,5})?$");

    /**
     * Splits a comma-separated origin list: entries are trimmed, one trailing {@code /} is removed,
     * empty entries are ignored, and anything that is not a plain {@code http(s)://host[:port]} origin
     * ({@code *}, a path, a placeholder such as {@code https://<domaine_dashboard>}) is dropped with a WARN.
     */
    static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String entry : raw.split(",")) {
            String origin = entry.trim();
            if (origin.endsWith("/")) {
                origin = origin.substring(0, origin.length() - 1);
            }
            if (origin.isEmpty()) {
                continue;
            }
            if (!ORIGIN.matcher(origin).matches()) {
                log.warn("[CORS] {} entry ignored — not an http(s)://host[:port] origin: '{}'",
                        DASHBOARD_ORIGIN_ENV, origin);
                continue;
            }
            if (!out.contains(origin)) {
                out.add(origin);
            }
        }
        return List.copyOf(out);
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
