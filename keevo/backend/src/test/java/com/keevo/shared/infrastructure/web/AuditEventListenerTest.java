package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import com.keevo.identity.auth.domain.model.PasswordResetRequestedEvent;
import com.keevo.identity.auth.domain.model.PasswordResetEvent;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetByOwnerEvent;
import com.keevo.identity.employee.domain.event.EmployeeRoleChangedEvent;
import com.keevo.identity.employee.domain.event.EmployeeUpdatedEvent;
import com.keevo.identity.onboarding.domain.model.OnboardingCompletedEvent;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditEventListenerTest — TDD tests for AuditEventListener.
 *
 * <p>RED → GREEN:
 * - RED: AuditEventListener constructor still takes no args / no AuditPort injection.
 * - GREEN: After wiring AuditPort + ObjectMapper in AuditEventListener.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventListener")
class AuditEventListenerTest {

    @Mock
    AuditPort auditPort;

    @Mock
    UserRepository userRepository;

    AuditEventListener listener;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        listener = new AuditEventListener(auditPort, objectMapper, userRepository);
        TenantContext.clear(); // ensure clean thread state before each test
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear(); // always clean up thread state
    }

    // ── UserRegisteredEvent ───────────────────────────────────────────────────

    @Test
    @DisplayName("on(UserRegisteredEvent) calls auditPort.record() with USER_REGISTERED action")
    void onUserRegistered_callsAuditPort() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UserRegisteredEvent event = UserRegisteredEvent.of(userId, tenantId, "KV-ABC123", "kv_abc123");

        listener.on(event);

        verify(auditPort).record(
                eq(userId),               // actorId
                eq("kv_abc123"),          // tenantId = schemaName
                eq("USER_REGISTERED"),    // action
                eq("User"),               // entityType
                eq(userId),               // entityId (same as actorId for registration)
                eq(null),                 // valueBefore — null for creation
                any()                     // valueAfter — JSON with tenantCode + schemaName
        );
    }

    @Test
    @DisplayName("on(UserRegisteredEvent) sets TenantContext before calling auditPort.record() — public endpoint")
    void onUserRegistered_setsTenantContextBeforeRecord() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UserRegisteredEvent event = UserRegisteredEvent.of(userId, tenantId, "KV-ABC123", "kv_abc123");

        // Verify TenantContext value AT THE MOMENT auditPort.record() is called using doAnswer
        doAnswer(inv -> {
            // Inside the invocation — TenantContext must already be set
            String currentTenantCtx = TenantContext.getCurrentTenant();
            assertThat(currentTenantCtx)
                    .as("TenantContext MUST be set to schemaName when auditPort.record() is called")
                    .isEqualTo("kv_abc123");
            return null;
        }).when(auditPort).record(any(), any(), any(), any(), any(), any(), any());

        listener.on(event);

        // After the event handler completes, TenantContext MUST be cleared
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared in finally block after on(UserRegisteredEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(UserRegisteredEvent) clears TenantContext in finally block even if auditPort throws")
    void onUserRegistered_clearsTenantContext_evenOnException() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UserRegisteredEvent event = UserRegisteredEvent.of(userId, tenantId, "KV-ABC123", "kv_abc123");

        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
                .when(auditPort).record(any(), any(), any(), any(), any(), any(), any());

        // Should not propagate — listener logs error
        try {
            listener.on(event);
        } catch (RuntimeException ignored) {
            // Depending on implementation: swallowed or rethrown — either way, TenantContext must be cleared
        }

        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared even when auditPort throws")
                .isNull();
    }

    // ── UserAuthenticatedEvent ────────────────────────────────────────────────

    @Test
    @DisplayName("on(UserAuthenticatedEvent) calls auditPort.record() with USER_AUTHENTICATED action")
    void onUserAuthenticated_callsAuditPort() {
        UUID userId = UUID.randomUUID();
        UserAuthenticatedEvent event = new UserAuthenticatedEvent(
                userId, "kv_xyz999", "OWNER", "192.168.1.1", Instant.now());

        listener.on(event);

        verify(auditPort).record(
                eq(userId),
                eq("kv_xyz999"),
                eq("USER_AUTHENTICATED"),
                eq("User"),
                eq(userId),
                eq(null),
                any()
        );
    }

    @Test
    @DisplayName("on(UserAuthenticatedEvent) sets TenantContext = event.tenantId() before record() — public endpoint")
    void onUserAuthenticated_setsTenantContextBeforeRecord() {
        UUID userId = UUID.randomUUID();
        UserAuthenticatedEvent event = new UserAuthenticatedEvent(
                userId, "kv_xyz999", "OWNER", null, Instant.now());

        doAnswer(inv -> {
            assertThat(TenantContext.getCurrentTenant())
                    .as("TenantContext MUST be set to event.tenantId() (schemaName) at record() call time")
                    .isEqualTo("kv_xyz999");
            return null;
        }).when(auditPort).record(any(), any(), any(), any(), any(), any(), any());

        listener.on(event);

        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared after on(UserAuthenticatedEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(UserAuthenticatedEvent) includes 'unknown' ip when ipAddress is null")
    void onUserAuthenticated_nullIpAddress_usesUnknown() {
        UUID userId = UUID.randomUUID();
        UserAuthenticatedEvent event = new UserAuthenticatedEvent(
                userId, "kv_xyz999", "OWNER", null, Instant.now());

        listener.on(event);

        // Capture the valueAfter argument via verify() + ArgumentCaptor (correct usage)
        ArgumentCaptor<String> valueAfterCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditPort).record(
                any(), any(), any(), any(), any(),
                any(),            // valueBefore
                valueAfterCaptor.capture()  // valueAfter
        );

        // valueAfter JSON should contain "unknown" for ip
        assertThat(valueAfterCaptor.getValue()).contains("unknown");
    }

    // ── OnboardingCompletedEvent ──────────────────────────────────────────────

    @Test
    @DisplayName("on(OnboardingCompletedEvent) calls auditPort.record() with ONBOARDING_COMPLETED action")
    void onOnboardingCompleted_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        OnboardingCompletedEvent event = new OnboardingCompletedEvent(
                "kv_aaa111", SectorType.CLOTHING, "Ma Boutique", 5, actorId, Instant.now());

        listener.on(event);

        verify(auditPort).record(
                eq(actorId),
                eq("kv_aaa111"),
                eq("ONBOARDING_COMPLETED"),
                eq("Tenant"),
                eq(actorId),
                eq(null),
                any()
        );
    }

    @Test
    @DisplayName("on(OnboardingCompletedEvent) does NOT touch TenantContext — authenticated endpoint")
    void onOnboardingCompleted_doesNotSetTenantContext() {
        // OnboardingCompleted fires on /api/v1/onboarding/complete (authenticated endpoint)
        // JwtAuthFilter has already set TenantContext — listener must NOT override it
        // TenantContext.set() equivalent
        TenantContext.setCurrentTenant("kv_already_set");

        UUID actorId = UUID.randomUUID();
        OnboardingCompletedEvent event = new OnboardingCompletedEvent(
                "kv_aaa111", SectorType.CLOTHING, "Ma Boutique", 5, actorId, Instant.now());

        listener.on(event);

        // TenantContext should remain as-is (not cleared by onboarding handler)
        assertThat(TenantContext.getCurrentTenant())
                .as("on(OnboardingCompletedEvent) must NOT clear TenantContext set by JwtAuthFilter")
                .isEqualTo("kv_already_set");

        TenantContext.clear(); // cleanup after test
    }

    // ── Story 14.11 — Employee events (authenticated endpoints) ──────────────

    @Test
    @DisplayName("on(EmployeeUpdatedEvent) calls auditPort.record() with EMPLOYEE_UPDATED action")
    void onEmployeeUpdated_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeeUpdatedEvent event = new EmployeeUpdatedEvent(
                actorId, "kv_abc123", employeeId,
                List.of("firstName", "phoneNumber"), Instant.now());

        listener.on(event);

        verify(auditPort).record(
                eq(actorId),
                eq("kv_abc123"),
                eq("EMPLOYEE_UPDATED"),
                eq("Employee"),
                eq(employeeId),
                eq(null),
                any()
        );
    }

    @Test
    @DisplayName("on(EmployeeRoleChangedEvent) calls auditPort.record() with before/after role")
    void onEmployeeRoleChanged_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeeRoleChangedEvent event = new EmployeeRoleChangedEvent(
                actorId, "kv_abc123", employeeId,
                "EMPLOYEE", "OWNER", Instant.now());

        listener.on(event);

        verify(auditPort).record(
                eq(actorId),
                eq("kv_abc123"),
                eq("EMPLOYEE_ROLE_CHANGED"),
                eq("Employee"),
                eq(employeeId),
                any(),  // valueBefore = JSON with previousRole
                any()   // valueAfter = JSON with newRole
        );
    }

    @Test
    @DisplayName("on(EmployeePasswordSetByOwnerEvent) calls auditPort.record() with forcedReset=true")
    void onEmployeePasswordSetByOwner_callsAuditPort() {
        UUID actorId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        EmployeePasswordSetByOwnerEvent event = new EmployeePasswordSetByOwnerEvent(
                actorId, "kv_abc123", employeeId, Instant.now());

        listener.on(event);

        verify(auditPort).record(
                eq(actorId),
                eq("kv_abc123"),
                eq("EMPLOYEE_PASSWORD_SET_BY_OWNER"),
                eq("Employee"),
                eq(employeeId),
                eq(null),
                any()   // valueAfter = JSON with forcedReset=true
        );
    }

    // ── PasswordResetRequestedEvent (Story 14.12) ─────────────────────────

    @Test
    @DisplayName("on(PasswordResetRequestedEvent) audits in all user memberships")
    void onPasswordResetRequested_auditsInAllMemberships() {
        UUID userId = UUID.randomUUID();
        var m1 = new UserMembershipInfo("KV-AAA", "Tenant A", "OWNER", "kv_aaa");
        var m2 = new UserMembershipInfo("KV-BBB", "Tenant B", "EMPLOYEE", "kv_bbb");
        when(userRepository.findMembershipsWithTenantInfo(userId)).thenReturn(List.of(m1, m2));

        // Capture TenantContext at the moment record() is called — must match the
        // membership's schemaName (cross-tenant loop, D4).
        doAnswer(invocation -> {
            String currentTenant = TenantContext.getCurrentTenant();
            String expectedSchema = invocation.getArgument(1); // 2nd arg = tenantId/schema
            assertThat(currentTenant)
                    .as("TenantContext MUST be set to schemaName when auditPort.record() is called")
                    .isEqualTo(expectedSchema);
            return null;
        }).when(auditPort).record(any(), any(), any(), any(), any(), any(), any());

        var event = new PasswordResetRequestedEvent(userId, "+237600000000", Instant.now());
        listener.on(event);

        // Must audit in BOTH tenants
        verify(auditPort).record(
                eq(userId), eq("kv_aaa"), eq("PASSWORD_RESET_REQUESTED"),
                eq("User"), eq(userId), eq(null), any());
        verify(auditPort).record(
                eq(userId), eq("kv_bbb"), eq("PASSWORD_RESET_REQUESTED"),
                eq("User"), eq(userId), eq(null), any());

        // After the event handler completes, TenantContext MUST be cleared
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared after on(PasswordResetRequestedEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(PasswordResetEvent) audits in all user memberships")
    void onPasswordReset_auditsInAllMemberships() {
        UUID userId = UUID.randomUUID();
        var m1 = new UserMembershipInfo("KV-AAA", "Tenant A", "OWNER", "kv_aaa");
        when(userRepository.findMembershipsWithTenantInfo(userId)).thenReturn(List.of(m1));

        // Capture TenantContext at record() time — must equal schemaName
        doAnswer(invocation -> {
            String currentTenant = TenantContext.getCurrentTenant();
            String expectedSchema = invocation.getArgument(1);
            assertThat(currentTenant)
                    .as("TenantContext MUST be set to schemaName when auditPort.record() is called")
                    .isEqualTo(expectedSchema);
            return null;
        }).when(auditPort).record(any(), any(), any(), any(), any(), any(), any());

        var event = new PasswordResetEvent(userId, Instant.now());
        listener.on(event);

        verify(auditPort).record(
                eq(userId), eq("kv_aaa"), eq("PASSWORD_RESET"),
                eq("User"), eq(userId), eq(null), any());

        // After the event handler completes, TenantContext MUST be cleared
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared after on(PasswordResetEvent)")
                .isNull();
    }

    @Test
    @DisplayName("on(PasswordResetEvent) continues loop even if one tenant's audit throws")
    void onPasswordReset_continuesLoopOnAuditFailure() {
        UUID userId = UUID.randomUUID();
        var m1 = new UserMembershipInfo("KV-AAA", "Tenant A", "OWNER", "kv_aaa");
        var m2 = new UserMembershipInfo("KV-BBB", "Tenant B", "EMPLOYEE", "kv_bbb");
        var m3 = new UserMembershipInfo("KV-CCC", "Tenant C", "OWNER", "kv_ccc");
        when(userRepository.findMembershipsWithTenantInfo(userId))
                .thenReturn(List.of(m1, m2, m3));

        // First tenant's audit throws — listener must catch, log, continue to next tenant
        doThrow(new RuntimeException("DB connection lost"))
                .when(auditPort).record(eq(userId), eq("kv_aaa"), any(), any(), any(), any(), any());

        var event = new PasswordResetEvent(userId, Instant.now());
        // Must NOT throw — best-effort audit per tenant
        listener.on(event);

        // kv_aaa failed, but kv_bbb and kv_ccc must still be attempted
        verify(auditPort).record(eq(userId), eq("kv_aaa"), eq("PASSWORD_RESET"),
                eq("User"), eq(userId), eq(null), any());
        verify(auditPort).record(eq(userId), eq("kv_bbb"), eq("PASSWORD_RESET"),
                eq("User"), eq(userId), eq(null), any());
        verify(auditPort).record(eq(userId), eq("kv_ccc"), eq("PASSWORD_RESET"),
                eq("User"), eq(userId), eq(null), any());

        // TenantContext MUST be cleared even after the exception path
        assertThat(TenantContext.getCurrentTenant())
                .as("TenantContext MUST be cleared even when audit throws")
                .isNull();
    }
}
