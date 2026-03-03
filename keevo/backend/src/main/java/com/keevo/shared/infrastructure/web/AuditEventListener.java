package com.keevo.shared.infrastructure.web;

import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AuditEventListener — Listens to domain events and writes to audit log.
 *
 * <p>GoF Pattern: Observer — reacts to domain events published via Spring's
 * ApplicationEventPublisher without coupling the domain to infrastructure.
 *
 * <p>Story 1.2: Stub implementation — logs event details only.
 * Full audit_log persistence implemented in Story 1.7.
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    /**
     * Handle user registered event: log for now, persist to audit_log in Story 1.7.
     *
     * @param event the UserRegisteredEvent published by RegistrationService
     */
    @EventListener
    public void on(UserRegisteredEvent event) {
        // Story 1.7 will replace this log with a real insert into audit_log table
        log.info("AUDIT: user_registered userId={} tenantId={} tenantCode={} schemaName={} at={}",
                event.userId(),
                event.tenantId(),
                event.tenantCode(),
                event.schemaName(),
                event.occurredAt());
    }
}
