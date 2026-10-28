package com.keevo.shared.infrastructure.observability;

import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 11.2 — the scrape guard inside the real {@code SecurityFilterChain}: it answers before
 * {@code JwtAuthFilter} (the scrape token is never parsed as a JWT) and before authorization.
 * A stub controller stands in for the actuator endpoint.
 */
@DisplayName("SecurityConfig — /actuator/prometheus guard through the filter chain")
class PrometheusScrapeSecurityChainTest {

    @RestController
    static class StubActuator {
        @GetMapping("/actuator/prometheus")
        ResponseEntity<String> prometheus() {
            return ResponseEntity.ok("keevo_sales_total 1.0\n");
        }
    }

    abstract static class ChainBase {
        @Autowired MockMvc mockMvc;
        @MockitoBean JwtAuthFilter jwtAuthFilter;

        @BeforeEach
        void passThroughJwtFilter() throws Exception {
            doAnswer(inv -> {
                inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(jwtAuthFilter).doFilter(any(), any(), any());
        }
    }

    @Nested
    @WebMvcTest(controllers = StubActuator.class)
    @Import({SecurityConfig.class, StubActuator.class})
    @TestPropertySource(properties = "keevo.observability.prometheus-token=chain-token")
    @DisplayName("token configured")
    class TokenConfigured extends ChainBase {

        @Test
        @DisplayName("correct token → 200")
        void ok() throws Exception {
            mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer chain-token"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("keevo_sales_total 1.0\n"));
        }

        @Test
        @DisplayName("JWT-shaped wrong token → 401 before JwtAuthFilter runs")
        void jwtLikeTokenNeverReachesJwtFilter() throws Exception {
            mockMvc.perform(get("/actuator/prometheus")
                            .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.sig"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
            verify(jwtAuthFilter, never()).doFilter(any(), any(), any());
        }

        @Test
        @DisplayName("no token → 401")
        void missing() throws Exception {
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
        }
    }

    @Nested
    @WebMvcTest(controllers = StubActuator.class)
    @Import({SecurityConfig.class, StubActuator.class})
    @TestPropertySource(properties = "keevo.observability.prometheus-token=")
    @DisplayName("token unset")
    class TokenUnset extends ChainBase {

        @Test
        @DisplayName("scrape disabled → 404, even with a bearer header")
        void notFound() throws Exception {
            mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer anything"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isNotFound());
        }
    }
}
