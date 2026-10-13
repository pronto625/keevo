package com.keevo.admin.alerts.adapter.in.rest;

import com.keevo.admin.alerts.domain.model.AdminAlert;
import com.keevo.admin.alerts.domain.model.AlertSeverity;
import com.keevo.admin.alerts.domain.port.in.CountAdminAlertsUseCase;
import com.keevo.admin.alerts.domain.port.in.HandleAdminAlertUseCase;
import com.keevo.admin.alerts.domain.port.in.ListAdminAlertsUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAlertController (Story 9-5)")
class AdminAlertControllerTest {

    @Mock ListAdminAlertsUseCase listAlerts;
    @Mock HandleAdminAlertUseCase handleAlert;
    @Mock CountAdminAlertsUseCase countAlerts;

    MockMvc mockMvc;
    UUID actor;

    @BeforeEach
    void setUp() {
        ProxyFactory factory = new ProxyFactory(new AdminAlertController(listAlerts, handleAlert, countAlerts));
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
        actor = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void listContract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(listAlerts.listAlerts(false)).thenReturn(List.of(new AdminAlert("feedback:abc", AlertSeverity.HIGH,
                "Retour utilisateur prioritaire", "Boutique Awa — Problème signalé", Instant.parse("2026-10-13T10:00:00Z"),
                false, null)));

        mockMvc.perform(get("/api/v1/admin/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").value("feedback:abc"))
                .andExpect(jsonPath("$.data[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.data[0].title").value("Retour utilisateur prioritaire"))
                .andExpect(jsonPath("$.data[0].description").value("Boutique Awa — Problème signalé"))
                .andExpect(jsonPath("$.data[0].occurredAt").value("2026-10-13T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].handled").value(false))
                .andExpect(jsonPath("$.data[0].handledAt").doesNotExist());
    }

    @Test
    void includeHandled() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(listAlerts.listAlerts(true)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/admin/alerts").param("includeHandled", "true"))
                .andExpect(status().isOk());
        verify(listAlerts).listAlerts(true);

        mockMvc.perform(get("/api/v1/admin/alerts").param("includeHandled", "peut-être"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    void handleReturns204() throws Exception {
        authenticateAs("SUPER_ADMIN");
        mockMvc.perform(post("/api/v1/admin/alerts/{key}/handle", "sync-silence:abc:123"))
                .andExpect(status().isNoContent());
        verify(handleAlert).markHandled("sync-silence:abc:123", actor);
    }

    @Test
    void handleMalformedKey422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        doThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "Alerte inconnue"))
                .when(handleAlert).markHandled(eq("BAD KEY"), any());
        mockMvc.perform(post("/api/v1/admin/alerts/{key}/handle", "BAD KEY"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    void countContract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(countAlerts.countUnhandled(AlertSeverity.HIGH)).thenReturn(3L);
        when(countAlerts.countUnhandled(null)).thenReturn(7L);

        mockMvc.perform(get("/api/v1/admin/alerts/count").param("severity", "high"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));
        mockMvc.perform(get("/api/v1/admin/alerts/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(7));
        mockMvc.perform(get("/api/v1/admin/alerts/count").param("severity", "CRITICAL"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
    }

    @Test
    void ownerIsForbidden() throws Exception {
        authenticateAs("OWNER");
        mockMvc.perform(get("/api/v1/admin/alerts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/admin/alerts/{key}/handle", "feedback:abc"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/alerts/count"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(listAlerts, handleAlert, countAlerts);
    }
}
