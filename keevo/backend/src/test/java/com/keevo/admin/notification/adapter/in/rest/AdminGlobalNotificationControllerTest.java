package com.keevo.admin.notification.adapter.in.rest;

import com.keevo.admin.notification.domain.model.GlobalNotification;
import com.keevo.admin.notification.domain.model.GlobalNotificationFailureReason;
import com.keevo.admin.notification.domain.model.PushTenantRef;
import com.keevo.admin.notification.domain.model.GlobalNotificationStatus;
import com.keevo.admin.notification.domain.model.GlobalNotificationTarget;
import com.keevo.admin.notification.domain.port.in.CancelGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.EstimateGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.ListGlobalNotificationsUseCase;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationCommand;
import com.keevo.admin.notification.domain.port.in.ScheduleGlobalNotificationUseCase;
import com.keevo.admin.notification.domain.port.in.SearchPushTenantsUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminGlobalNotificationController (Story 9-5 — global push)")
class AdminGlobalNotificationControllerTest {

    @Mock ScheduleGlobalNotificationUseCase scheduleUseCase;
    @Mock ListGlobalNotificationsUseCase listUseCase;
    @Mock CancelGlobalNotificationUseCase cancelUseCase;
    @Mock EstimateGlobalNotificationUseCase estimateUseCase;
    @Mock SearchPushTenantsUseCase searchUseCase;

    MockMvc mockMvc;
    UUID actor;

    @BeforeEach
    void setUp() {
        ProxyFactory factory = new ProxyFactory(new AdminGlobalNotificationController(
                scheduleUseCase, listUseCase, cancelUseCase, estimateUseCase, searchUseCase));
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
    void schedule_contract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        when(scheduleUseCase.schedule(any())).thenReturn(new ScheduleGlobalNotificationUseCase.Scheduled(id, 5));

        mockMvc.perform(post("/api/v1/admin/notifications/global").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Maintenance\",\"body\":\"Ce soir\",\"target\":\"ALL\",\"scheduledAt\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.recipientEstimate").value(5));

        ArgumentCaptor<ScheduleGlobalNotificationCommand> cmd = ArgumentCaptor.forClass(ScheduleGlobalNotificationCommand.class);
        verify(scheduleUseCase).schedule(cmd.capture());
        assertThat(cmd.getValue()).isEqualTo(
                new ScheduleGlobalNotificationCommand("Maintenance", "Ce soir", "ALL", null, null, actor));
    }

    @Test
    void schedule_validationError_is422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(scheduleUseCase.schedule(any())).thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "invalid",
                Map.of("targetTenantId", "Choisissez l'entreprise")));

        mockMvc.perform(post("/api/v1/admin/notifications/global").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"body\":\"B\",\"target\":\"TENANT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.targetTenantId").value("Choisissez l'entreprise"));
    }

    @Test
    void schedule_withoutBody_is422() throws Exception {
        authenticateAs("SUPER_ADMIN");
        mockMvc.perform(post("/api/v1/admin/notifications/global").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.title").exists())
                .andExpect(jsonPath("$.details.body").exists())
                .andExpect(jsonPath("$.details.target").exists());
        verifyNoInteractions(scheduleUseCase);
    }

    @Test
    void history_contract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        when(listUseCase.history()).thenReturn(List.of(new GlobalNotification(id, "Maintenance", "Ce soir",
                GlobalNotificationTarget.TENANT, UUID.randomUUID(), "Boutique Awa",
                Instant.parse("2026-10-13T10:00:00Z"), GlobalNotificationStatus.SENT, 5, 4, 1, null, actor,
                Instant.parse("2026-10-13T09:59:00Z"), Instant.parse("2026-10-13T10:00:30Z"))));

        mockMvc.perform(get("/api/v1/admin/notifications/global"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].title").value("Maintenance"))
                .andExpect(jsonPath("$.data[0].body").value("Ce soir"))
                .andExpect(jsonPath("$.data[0].target").value("TENANT"))
                .andExpect(jsonPath("$.data[0].targetTenantName").value("Boutique Awa"))
                .andExpect(jsonPath("$.data[0].targetTenantId").doesNotExist())
                .andExpect(jsonPath("$.data[0].status").value("SENT"))
                .andExpect(jsonPath("$.data[0].scheduledAt").value("2026-10-13T10:00:00Z"))
                .andExpect(jsonPath("$.data[0].sentAt").value("2026-10-13T10:00:30Z"))
                .andExpect(jsonPath("$.data[0].recipientCount").value(5))
                .andExpect(jsonPath("$.data[0].deliveredCount").value(4))
                .andExpect(jsonPath("$.data[0].failureCount").value(1))
                .andExpect(jsonPath("$.data[0].failureReason").doesNotExist())
                .andExpect(jsonPath("$.data[0].createdBy").doesNotExist());
    }

    @Test
    void cancel_scheduled_is204() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/notifications/global/" + id + "/cancel"))
                .andExpect(status().isNoContent());
        verify(cancelUseCase).cancel(id, actor);
    }

    @Test
    void cancel_sent_is409() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.INVALID_STATE, "not scheduled")).when(cancelUseCase).cancel(id, actor);
        mockMvc.perform(post("/api/v1/admin/notifications/global/" + id + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("INVALID_STATE"));
    }

    @Test
    void cancel_malformedId_is404() throws Exception {
        authenticateAs("SUPER_ADMIN");
        mockMvc.perform(post("/api/v1/admin/notifications/global/abc/cancel"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(cancelUseCase);
    }

    @Test
    void history_failedRow_exposesTheReason() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(listUseCase.history()).thenReturn(List.of(new GlobalNotification(UUID.randomUUID(), "T", "B",
                GlobalNotificationTarget.ALL, null, null, Instant.parse("2026-10-13T10:00:00Z"),
                GlobalNotificationStatus.FAILED, 0, 0, 0, GlobalNotificationFailureReason.PUSH_UNAVAILABLE, actor,
                Instant.parse("2026-10-13T10:00:00Z"), Instant.parse("2026-10-13T10:01:00Z"))));
        mockMvc.perform(get("/api/v1/admin/notifications/global"))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].failureReason").value("PUSH_UNAVAILABLE"));
    }

    @Test
    void tenants_search_contract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        when(searchUseCase.searchTenants("Awa")).thenReturn(List.of(new PushTenantRef(id, "Boutique Awa", "FREE")));
        mockMvc.perform(get("/api/v1/admin/notifications/global/tenants").param("search", "Awa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].name").value("Boutique Awa"))
                .andExpect(jsonPath("$.data[0].planType").doesNotExist());
    }

    @Test
    void estimate_contract() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(estimateUseCase.estimate("PREMIUM", null)).thenReturn(12L);
        mockMvc.perform(get("/api/v1/admin/notifications/global/estimate").param("target", "PREMIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(12));
    }

    @Test
    void owner_isForbidden_everywhere() throws Exception {
        authenticateAs("OWNER");
        mockMvc.perform(get("/api/v1/admin/notifications/global"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/admin/notifications/global").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"body\":\"B\",\"target\":\"ALL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/notifications/global/" + UUID.randomUUID() + "/cancel"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/notifications/global/estimate").param("target", "ALL"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/notifications/global/tenants").param("search", "a"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(scheduleUseCase, listUseCase, cancelUseCase, estimateUseCase, searchUseCase);
    }
}
