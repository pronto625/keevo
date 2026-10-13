package com.keevo.admin.subscription.adapter.in.rest;

import com.keevo.admin.subscription.domain.model.PaymentMethod;
import com.keevo.admin.subscription.domain.model.PlanAlert;
import com.keevo.admin.subscription.domain.model.TenantPayment;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumCommand;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumUseCase;
import com.keevo.admin.subscription.domain.port.in.DowngradeToFreeUseCase;
import com.keevo.admin.subscription.domain.port.in.ListPlanAlertsUseCase;
import com.keevo.admin.subscription.domain.port.in.ListTenantPaymentsUseCase;
import com.keevo.admin.subscription.domain.port.in.ReactivateTenantUseCase;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantCommand;
import com.keevo.admin.subscription.domain.port.in.SuspendTenantUseCase;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.subscription.plan.domain.port.in.CurrentPlan;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminTenantPlanController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AdminTenantPlanController (Story 9-2)")
class AdminTenantPlanControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ActivatePremiumUseCase activatePremium;
    @MockitoBean DowngradeToFreeUseCase downgradeToFree;
    @MockitoBean SuspendTenantUseCase suspendTenant;
    @MockitoBean ReactivateTenantUseCase reactivateTenant;
    @MockitoBean ListTenantPaymentsUseCase listPayments;
    @MockitoBean ListPlanAlertsUseCase listAlerts;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String BASE = "/api/v1/admin/tenants/{tenantId}";
    private static final String VALID_ACTIVATION =
            "{\"expiresAt\":\"2026-12-31\",\"amountXaf\":10000,\"method\":\"MOBILE_MONEY\",\"note\":\"OM ref 42\"}";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Activation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /plan/premium as SUPER_ADMIN → 200 with the new plan; command carries actor, tenant, parsed fields")
    void activate_ok() throws Exception {
        as("ROLE_SUPER_ADMIN");
        Instant until = Instant.parse("2026-12-31T22:59:59Z");
        when(activatePremium.activatePremium(any())).thenReturn(new CurrentPlan(PlanType.PREMIUM, until));

        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ACTIVATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planType").value("PREMIUM"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-12-31T22:59:59Z"));

        ArgumentCaptor<ActivatePremiumCommand> cmd = ArgumentCaptor.forClass(ActivatePremiumCommand.class);
        verify(activatePremium).activatePremium(cmd.capture());
        assertThat(cmd.getValue().actorId()).isEqualTo(ADMIN_ID);
        assertThat(cmd.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(cmd.getValue().expiresAt()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(cmd.getValue().amountXaf()).isEqualTo(10_000);
        assertThat(cmd.getValue().method()).isEqualTo(PaymentMethod.MOBILE_MONEY);
        assertThat(cmd.getValue().paidAt()).isNull();
        assertThat(cmd.getValue().note()).isEqualTo("OM ref 42");
    }

    @Test
    @DisplayName("POST /plan/premium with a plain paidAt date → start of that day in WAT")
    void activate_paidAtDate() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(activatePremium.activatePremium(any())).thenReturn(new CurrentPlan(PlanType.PREMIUM, Instant.now()));

        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-12-31\",\"amountXaf\":100,\"method\":\"CASH\",\"paidAt\":\"2026-10-10\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ActivatePremiumCommand> cmd = ArgumentCaptor.forClass(ActivatePremiumCommand.class);
        verify(activatePremium).activatePremium(cmd.capture());
        assertThat(cmd.getValue().paidAt()).isEqualTo(Instant.parse("2026-10-09T23:00:00Z"));
    }

    @Test
    @DisplayName("POST /plan/premium as OWNER → 403 FORBIDDEN, use case never called")
    void activate_owner_forbidden() throws Exception {
        as("ROLE_OWNER");

        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ACTIVATION))
                .andExpect(status().isForbidden());

        verifyNoInteractions(activatePremium);
    }

    @Test
    @DisplayName("POST /plan/premium with amount 0, unknown method or malformed date → 422 VALIDATION_ERROR")
    void activate_invalid_422() throws Exception {
        as("ROLE_SUPER_ADMIN");

        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-12-31\",\"amountXaf\":0,\"method\":\"MOBILE_MONEY\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.amountXaf").exists());
        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-12-31\",\"amountXaf\":3000000000,\"method\":\"CASH\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.amountXaf").value("Montant trop élevé"));
        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-12-31\",\"amountXaf\":100,\"method\":\"BITCOIN\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.method").exists());
        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresAt\":\"2026-02-30\",\"amountXaf\":100,\"method\":\"CASH\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.expiresAt").exists());

        verifyNoInteractions(activatePremium);
    }

    @Test
    @DisplayName("POST /plan/premium on an unknown tenant → 404 TENANT_NOT_FOUND")
    void activate_unknownTenant_404() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(activatePremium.activatePremium(any()))
                .thenThrow(new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found"));

        mockMvc.perform(post(BASE + "/plan/premium", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_ACTIVATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("TENANT_NOT_FOUND"));
    }

    // ── Downgrade ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /plan/free → 200 FREE; already FREE → 409 INVALID_STATE")
    void downgrade() throws Exception {
        as("ROLE_SUPER_ADMIN");

        mockMvc.perform(post(BASE + "/plan/free", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"non renouvelé\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planType").value("FREE"));

        doThrow(new DomainException(ErrorCode.INVALID_STATE, "already free"))
                .when(downgradeToFree).downgradeToFree(any());
        mockMvc.perform(post(BASE + "/plan/free", TENANT).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("INVALID_STATE"));
    }

    // ── Suspension ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /suspend with reason → 200, command carries the reason")
    void suspend_ok() throws Exception {
        as("ROLE_SUPER_ADMIN");

        mockMvc.perform(post(BASE + "/suspend", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"fraude\"}"))
                .andExpect(status().isOk());

        verify(suspendTenant).suspend(new SuspendTenantCommand(ADMIN_ID, TENANT, "fraude"));
    }

    @Test
    @DisplayName("POST /suspend with a blank reason or no body → 422 VALIDATION_ERROR")
    void suspend_blank_422() throws Exception {
        as("ROLE_SUPER_ADMIN");

        mockMvc.perform(post(BASE + "/suspend", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.reason").exists());
        mockMvc.perform(post(BASE + "/suspend", TENANT).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(suspendTenant);
    }

    @Test
    @DisplayName("POST /suspend on a DELETION_PENDING tenant → 409 INVALID_STATE")
    void suspend_invalidState_409() throws Exception {
        as("ROLE_SUPER_ADMIN");
        doThrow(new DomainException(ErrorCode.INVALID_STATE, "x")).when(suspendTenant).suspend(any());

        mockMvc.perform(post(BASE + "/suspend", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"fraude\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("INVALID_STATE"));
    }

    @Test
    @DisplayName("POST /reactivate → 200; EMPLOYEE → 403")
    void reactivate() throws Exception {
        as("ROLE_SUPER_ADMIN");
        mockMvc.perform(post(BASE + "/reactivate", TENANT)).andExpect(status().isOk());
        verify(reactivateTenant).reactivate(ADMIN_ID, TENANT);

        as("ROLE_EMPLOYEE");
        mockMvc.perform(post(BASE + "/reactivate", TENANT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        verifyNoMoreInteractions(reactivateTenant);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /payments → history contract, recorder shown as « Super admin », no ids")
    void payments_contract() throws Exception {
        as("ROLE_SUPER_ADMIN");
        UUID recorder = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(listPayments.listPayments(TENANT)).thenReturn(List.of(new TenantPayment(paymentId, TENANT, 10_000,
                PaymentMethod.CASH, Instant.parse("2026-10-13T09:00:00Z"),
                LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 12), "espèces", recorder)));

        mockMvc.perform(get(BASE + "/payments", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].paidAt").value("2026-10-13T09:00:00Z"))
                .andExpect(jsonPath("$.data[0].amountXaf").value(10000))
                .andExpect(jsonPath("$.data[0].method").value("CASH"))
                .andExpect(jsonPath("$.data[0].periodStart").value("2026-10-13"))
                .andExpect(jsonPath("$.data[0].periodEnd").value("2026-11-12"))
                .andExpect(jsonPath("$.data[0].note").value("espèces"))
                .andExpect(jsonPath("$.data[0].recordedByLabel").value("Super admin"))
                .andExpect(content().string(not(containsString(recorder.toString()))))
                .andExpect(content().string(not(containsString(paymentId.toString()))));
    }

    @Test
    @DisplayName("GET /api/v1/admin/subscriptions/alerts → expiring + expired entries; OWNER → 403")
    void alerts_contract() throws Exception {
        as("ROLE_SUPER_ADMIN");
        when(listAlerts.listAlerts()).thenReturn(List.of(
                new PlanAlert(TENANT, "Boutique Awa", PlanAlert.Kind.EXPIRING, "PREMIUM", 3,
                        Instant.parse("2026-10-16T22:59:59Z"), null),
                new PlanAlert(TENANT, "Boutique Awa", PlanAlert.Kind.EXPIRED, "PREMIUM_TRIAL", null, null,
                        Instant.parse("2026-10-12T01:00:00Z"))));

        mockMvc.perform(get("/api/v1/admin/subscriptions/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tenantName").value("Boutique Awa"))
                .andExpect(jsonPath("$.data[0].kind").value("EXPIRING"))
                .andExpect(jsonPath("$.data[0].daysLeft").value(3))
                .andExpect(jsonPath("$.data[0].plan").value("PREMIUM"))
                .andExpect(jsonPath("$.data[1].kind").value("EXPIRED"))
                .andExpect(jsonPath("$.data[1].occurredAt").value("2026-10-12T01:00:00Z"));

        as("ROLE_OWNER");
        mockMvc.perform(get("/api/v1/admin/subscriptions/alerts")).andExpect(status().isForbidden());
        verify(listAlerts, times(1)).listAlerts();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void as(String role) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    ADMIN_ID, null, List.of(new SimpleGrantedAuthority(role)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
