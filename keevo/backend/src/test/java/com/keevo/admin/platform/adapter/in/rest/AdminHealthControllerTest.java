package com.keevo.admin.platform.adapter.in.rest;

import com.keevo.admin.platform.domain.model.SystemHealth;
import com.keevo.admin.platform.domain.model.SystemHealthFixtures;
import com.keevo.admin.platform.domain.port.in.GetSystemHealthUseCase;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminHealthController (Story 9-5)")
class AdminHealthControllerTest {

    static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    @Mock GetSystemHealthUseCase useCase;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProxyFactory factory = new ProxyFactory(new AdminHealthController(useCase));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        mockMvc = MockMvcBuilders.standaloneSetup(factory.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static SystemHealth errorSpike() {
        return SystemHealthFixtures.healthy(NOW).uptime(99.9, false).errorRate(3.0, true)
                .connections(5, 10, false).refusals(12L, false).build();
    }

    @Test
    void healthContract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(useCase.health()).thenReturn(errorSpike());

        mockMvc.perform(get("/api/v1/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uptimePercent30d").value(99.9))
                .andExpect(jsonPath("$.data.uptimeBreached").value(false))
                .andExpect(jsonPath("$.data.p50Ms").value(40))
                .andExpect(jsonPath("$.data.p95Ms").value(300))
                .andExpect(jsonPath("$.data.p99Ms").value(800))
                .andExpect(jsonPath("$.data.latencyBreached").value(false))
                .andExpect(jsonPath("$.data.errorRatePercent1h").value(3.0))
                .andExpect(jsonPath("$.data.errorRateBreached").value(true))
                .andExpect(jsonPath("$.data.dbActiveConnections").value(5))
                .andExpect(jsonPath("$.data.dbMaxConnections").value(10))
                .andExpect(jsonPath("$.data.dbActiveConnectionsBreached").value(false))
                .andExpect(jsonPath("$.data.databaseOk").value(true))
                .andExpect(jsonPath("$.data.databaseBreached").value(false))
                .andExpect(jsonPath("$.data.syncRefusals1h").value(12))
                .andExpect(jsonPath("$.data.syncRefusalsBreached").value(false))
                .andExpect(jsonPath("$.data.lastSampleAt").value("2026-10-13T09:58:00Z"))
                .andExpect(jsonPath("$.data.checkedAt").value("2026-10-13T10:00:00Z"))
                .andExpect(jsonPath("$.data.incident").value(true))
                .andExpect(jsonPath("$.data.breachedMetrics[0]").value("Taux d'erreur au-dessus de 2 %"));
    }

    @Test
    void healthWithDatabaseDownStill200() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(useCase.health()).thenReturn(SystemHealthFixtures.healthy(NOW).databaseDown().build());

        mockMvc.perform(get("/api/v1/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.databaseOk").value(false))
                .andExpect(jsonPath("$.data.databaseBreached").value(true))
                .andExpect(jsonPath("$.data.uptimePercent30d").doesNotExist())
                .andExpect(jsonPath("$.data.breachedMetrics[0]").value("Base de données injoignable"));
    }

    @Test
    void statusContract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(useCase.recentHealth()).thenReturn(errorSpike());

        mockMvc.perform(get("/api/v1/admin/health/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident").value(true))
                .andExpect(jsonPath("$.data.breachedMetrics.length()").value(1))
                .andExpect(jsonPath("$.data.breachedMetrics[0]").value("Taux d'erreur au-dessus de 2 %"));
    }

    @Test
    void connectionsBreachedIsRed() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(useCase.health()).thenReturn(SystemHealthFixtures.healthy(NOW).connections(9, 10, true).build());
        mockMvc.perform(get("/api/v1/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dbActiveConnectionsBreached").value(true))
                .andExpect(jsonPath("$.data.breachedMetrics[0]").value("Connexions à la base au-dessus de 80 %"));
    }

    @Test
    void ownerIsForbidden() throws Exception {
        authenticateAs("OWNER");
        mockMvc.perform(get("/api/v1/admin/health"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/health/status"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }
}
