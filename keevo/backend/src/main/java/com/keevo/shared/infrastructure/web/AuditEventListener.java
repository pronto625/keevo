package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import com.keevo.identity.onboarding.domain.model.OnboardingCompletedEvent;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AuditEventListener — Listens to domain events and writes immutable entries to the audit log.
 *
 * <p>GoF Pattern: Observer — reacts to domain events published via Spring's
 * ApplicationEventPublisher without coupling the domain to infrastructure.
 *
 * <p>GoF Pattern: Template Method — all {@code on()} handlers follow the same skeleton:
 * <ol>
 *   <li>Manage TenantContext if needed (public endpoints only)</li>
 *   <li>Build valueBefore (null for creation/authentication events)</li>
 *   <li>Build valueAfter JSON from event payload</li>
 *   <li>Call {@code auditPort.record()} synchronously</li>
 *   <li>Log for observability</li>
 * </ol>
 *
 * <p><b>CRITICAL — TenantContext management rules:</b>
 * <ul>
 *   <li>{@code UserRegisteredEvent} — {@code /auth/register} is PUBLIC → JwtAuthFilter bypassed
 *       → MUST explicitly set TenantContext to {@code event.schemaName()} before record() +
 *       clear in finally.</li>
 *   <li>{@code UserAuthenticatedEvent} — {@code /auth/select-tenant} is PUBLIC (receives
 *       loginToken, not access JWT) → JwtAuthFilter bypassed → MUST set TenantContext to
 *       {@code event.tenantId()} (= schemaName resolved by SelectTenantService) before record().</li>
 *   <li>{@code OnboardingCompletedEvent} — {@code /api/v1/onboarding/complete} is AUTHENTICATED →
 *       JwtAuthFilter has already set TenantContext → NO manual management needed.</li>
 * </ul>
 *
 * <p>Story 1.8 — Full audit_log persistence replacing stub implementation.
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditPort auditPort;
    private final ObjectMapper objectMapper;

    public AuditEventListener(AuditPort auditPort, ObjectMapper objectMapper) {
        this.auditPort    = auditPort;
        this.objectMapper = objectMapper;
    }

    /**
     * Handle user registered event.
     *
     * <p><b>PUBLIC endpoint</b> — JwtAuthFilter bypassed → TenantContext NOT set.
     * Listener MUST set TenantContext manually before calling auditPort.record().
     */
    @EventListener
    public void on(UserRegisteredEvent event) {
        TenantContext.setCurrentTenant(event.schemaName()); // MANDATORY: /auth/register bypasses JwtAuthFilter
        try {
            auditPort.record(
                    event.userId(),
                    event.schemaName(),
                    "USER_REGISTERED",
                    "User",
                    event.userId(),
                    null,
                    toJson(Map.of("tenantCode", event.tenantCode(), "schemaName", event.schemaName()))
            );
            log.info("AUDIT: user_registered userId={} schema={}", event.userId(), event.schemaName());
        } finally {
            TenantContext.clear(); // always clear — thread may be reused for next request
        }
    }

    /**
     * Handle user authenticated event (two-step login: select-tenant).
     *
     * <p><b>PUBLIC endpoint</b> — {@code /auth/select-tenant} receives a {@code loginToken}
     * (scope=login_pending), NOT a full access JWT. JwtAuthFilter bypasses the request →
     * TenantContext NOT set. {@code event.tenantId()} holds the schemaName resolved by
     * SelectTenantService.
     */
    @EventListener
    public void on(UserAuthenticatedEvent event) {
        // /auth/select-tenant is public (loginToken ≠ access JWT) — JwtAuthFilter does not run
        // event.tenantId() = schemaName (kv_xxxxxx) resolved by SelectTenantService before publishing
        TenantContext.setCurrentTenant(event.tenantId());
        try {
            auditPort.record(
                    event.userId(),
                    event.tenantId(),
                    "USER_AUTHENTICATED",
                    "User",
                    event.userId(),
                    null,
                    toJson(Map.of(
                            "role", event.role(),
                            "ip",   event.ipAddress() != null ? event.ipAddress() : "unknown"
                    ))
            );
            log.info("AUDIT: user_authenticated userId={} schema={}", event.userId(), event.tenantId());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Handle onboarding completed event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — {@code /api/v1/onboarding/complete} requires a valid
     * access JWT. JwtAuthFilter has already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(OnboardingCompletedEvent event) {
        // Authenticated endpoint — JwtAuthFilter already set TenantContext; NO manual set/clear
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "ONBOARDING_COMPLETED",
                "Tenant",
                event.actorId(),
                null,
                toJson(Map.of(
                        "sectorType",       event.sectorType().name(),
                        "storeName",        event.storeName(),
                        "categoriesCreated", event.categoriesCreated()
                ))
        );
        log.info("AUDIT: onboarding_completed tenantId={} sector={} storeName={} categoriesCreated={} actorId={}",
                event.tenantId(), event.sectorType(), event.storeName(),
                event.categoriesCreated(), event.actorId());
    }

    // ── Template Method helper ────────────────────────────────────────────────

    /**
     * Serialize an object to a JSON string.
     * Returns "{}" on error — audit is best-effort on serialization.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("AuditEventListener: failed to serialize valueAfter to JSON — using {{}}: {}", e.getMessage());
            return "{}";
        }
    }
}
