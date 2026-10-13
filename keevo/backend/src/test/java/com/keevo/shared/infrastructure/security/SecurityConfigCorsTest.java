package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 11.4 AC5/AC6 — CORS allow-list built from {@code DASHBOARD_ORIGIN} and the active profiles.
 * Plain unit test: {@link MockEnvironment} stands in for the OS environment.
 */
@DisplayName("SecurityConfig — CORS allow-list (Story 11.4)")
class SecurityConfigCorsTest {

    private static final String DASHBOARD = "https://admin.keevo.cm";
    private static final String LOCALHOST = "http://localhost:3000";

    private static SecurityConfig config(String dashboardOrigin, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        if (dashboardOrigin != null) {
            env.setProperty("DASHBOARD_ORIGIN", dashboardOrigin);
        }
        return new SecurityConfig(mock(JwtAuthFilter.class), new ObjectMapper(), env);
    }

    private static CorsConfiguration cors(SecurityConfig securityConfig) {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/products"));
    }

    /** Runs a real preflight through Spring's CORS processor; returns the HTTP status. */
    private static int preflight(SecurityConfig securityConfig, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/products");
        request.addHeader(HttpHeaders.ORIGIN, origin);
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name());
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DefaultCorsProcessor().processRequest(cors(securityConfig), request, response);
        return response.getStatus();
    }

    @Test
    @DisplayName("prod: only DASHBOARD_ORIGIN, never localhost, never a wildcard")
    void prodAllowsOnlyDashboardOrigin() {
        CorsConfiguration cfg = cors(config(DASHBOARD, "prod"));

        assertThat(cfg.getAllowedOrigins()).containsExactly(DASHBOARD);
        assertThat(cfg.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cfg.getAllowCredentials()).isFalse(); // stateless bearer JWT, no cookies
    }

    @Test
    @DisplayName("prod: preflight from DASHBOARD_ORIGIN allowed, from localhost:3000 rejected (403)")
    void prodPreflight() throws Exception {
        SecurityConfig prod = config(DASHBOARD, "prod");

        assertThat(preflight(prod, DASHBOARD)).isEqualTo(200);
        assertThat(preflight(prod, LOCALHOST)).isEqualTo(403);
        assertThat(preflight(prod, "https://evil.example")).isEqualTo(403);
    }

    @Test
    @DisplayName("staging is hardened like prod")
    void stagingExcludesLocalhost() {
        assertThat(cors(config(DASHBOARD, "staging")).getAllowedOrigins()).containsExactly(DASHBOARD);
    }

    @Test
    @DisplayName("prod without DASHBOARD_ORIGIN: no browser origin allowed")
    void prodWithoutDashboardOriginAllowsNothing() throws Exception {
        SecurityConfig prod = config(null, "prod");

        assertThat(cors(prod).getAllowedOrigins()).isEmpty();
        assertThat(preflight(prod, LOCALHOST)).isEqualTo(403);
    }

    @Test
    @DisplayName("dev: localhost:3000 allowed (preflight 200), alongside DASHBOARD_ORIGIN")
    void devIncludesLocalhost() throws Exception {
        SecurityConfig dev = config(DASHBOARD, "dev");

        assertThat(cors(dev).getAllowedOrigins()).containsExactlyInAnyOrder(DASHBOARD, LOCALHOST);
        assertThat(preflight(dev, LOCALHOST)).isEqualTo(200);
    }

    @Test
    @DisplayName("no profile and no DASHBOARD_ORIGIN: localhost:3000 only")
    void defaultProfileLocalhostOnly() {
        assertThat(cors(config(null)).getAllowedOrigins()).containsExactly(LOCALHOST);
    }

    @Test
    @DisplayName("misspelled / unknown profile is hardened (fails closed): no localhost")
    void unknownProfileIsHardened() {
        assertThat(cors(config(DASHBOARD, "prd")).getAllowedOrigins()).containsExactly(DASHBOARD);
    }

    @Test
    @DisplayName("local and test profiles allow localhost:3000")
    void localAndTestProfilesAllowLocalhost() {
        assertThat(cors(config(null, "local")).getAllowedOrigins()).containsExactly(LOCALHOST);
        assertThat(cors(config(null, "test")).getAllowedOrigins()).containsExactly(LOCALHOST);
    }

    @Test
    @DisplayName("DASHBOARD_ORIGIN=\"a, b/,\" → origins a and b (trimmed, one trailing slash removed, empty dropped)")
    void commaSeparatedOrigins() {
        CorsConfiguration cfg = cors(config("https://a.keevo.cm, https://b.keevo.cm:8443/, ,", "prod"));

        assertThat(cfg.getAllowedOrigins()).containsExactly("https://a.keevo.cm", "https://b.keevo.cm:8443");
    }

    @Test
    @DisplayName("wildcard, path, placeholder and non-http entries are dropped")
    void malformedEntriesDropped() {
        assertThat(SecurityConfig.parseOrigins(
                "*, https://*.keevo.cm, https://a.keevo.cm/app, https://<domaine_dashboard>, "
                        + "ftp://a.keevo.cm, https://a.keevo.cm//, admin.keevo.cm, https://ok.keevo.cm"))
                .containsExactly("https://ok.keevo.cm");
    }
}
