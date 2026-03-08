package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

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

    AuditEventListener listener;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        listener = new AuditEventListener(auditPort, objectMapper);
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
}
