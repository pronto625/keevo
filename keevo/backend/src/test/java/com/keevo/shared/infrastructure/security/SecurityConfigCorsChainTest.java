package com.keevo.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 11.4 AC5 — CORS through the real {@code SecurityFilterChain}, prod profile,
 * {@code DASHBOARD_ORIGIN=https://admin.keevo.cm}. Proves there is a single CORS source (no MVC-level
 * wildcard mapping re-allowing what Security refused).
 */
@WebMvcTest(controllers = SecurityConfigCorsChainTest.StubController.class)
@Import({SecurityConfig.class, SecurityConfigCorsChainTest.StubController.class})
@ActiveProfiles("prod")
@TestPropertySource(properties = "DASHBOARD_ORIGIN=https://admin.keevo.cm")
@DisplayName("SecurityConfig — CORS through the full filter chain (prod)")
class SecurityConfigCorsChainTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtAuthFilter jwtAuthFilter;

    @RestController
    static class StubController {
        @GetMapping("/api/v1/__cors_test")
        ResponseEntity<Void> stub() {
            return ResponseEntity.ok().build();
        }
    }

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    private org.springframework.test.web.servlet.ResultActions preflight(String origin) throws Exception {
        return mockMvc.perform(options("/api/v1/__cors_test")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"));
    }

    @Test
    @DisplayName("preflight from DASHBOARD_ORIGIN → 200 with Access-Control-Allow-Origin, no credentials")
    void dashboardOriginAllowed() throws Exception {
        preflight("https://admin.keevo.cm")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://admin.keevo.cm"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("preflight from an unknown origin → 403, no ACAO header")
    void unknownOriginRejected() throws Exception {
        preflight("https://evil.example")
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("preflight from localhost:3000 in prod → 403, no ACAO header")
    void localhostRejectedInProd() throws Exception {
        preflight("http://localhost:3000")
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
