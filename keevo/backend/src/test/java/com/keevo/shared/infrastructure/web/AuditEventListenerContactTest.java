package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.contact.domain.event.ClientArchivedEvent;
import com.keevo.catalog.contact.domain.event.ClientCreatedEvent;
import com.keevo.catalog.contact.domain.event.SupplierArchivedEvent;
import com.keevo.catalog.contact.domain.event.SupplierCreatedEvent;
import com.keevo.shared.application.port.AuditPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Tests for Contact event handlers in AuditEventListener (Story 2.5).
 *
 * <p>All 4 events are on AUTHENTICATED endpoints → JwtAuthFilter already set
 * TenantContext → NO manual TenantContext management in these handlers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventListener — Contact Events (Story 2.5)")
class AuditEventListenerContactTest {

    @Mock AuditPort auditPort;

    AuditEventListener listener;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID CLIENT_ID   = UUID.randomUUID();
    private static final UUID SUPPLIER_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID    = UUID.randomUUID();
    private static final String TENANT    = "kv_abc123";
    private static final Instant NOW      = Instant.now();

    @BeforeEach
    void setUp() {
        listener = new AuditEventListener(auditPort, objectMapper);
    }

    // ── ClientCreatedEvent ────────────────────────────────────────────────────

    @Test
    @DisplayName("on(ClientCreatedEvent) calls auditPort.record with CLIENT_CREATED action")
    void on_clientCreated_records_audit() {
        ClientCreatedEvent event = new ClientCreatedEvent(CLIENT_ID, "Alice", ACTOR_ID, TENANT, NOW);

        listener.on(event);

        verify(auditPort).record(
                eq(ACTOR_ID),
                eq(TENANT),
                eq("CLIENT_CREATED"),
                eq("Client"),
                eq(CLIENT_ID),
                eq(null),   // no valueBefore on creation
                any()       // valueAfter JSON with name
        );
    }

    // ── ClientArchivedEvent ───────────────────────────────────────────────────

    @Test
    @DisplayName("on(ClientArchivedEvent) calls auditPort.record with CLIENT_ARCHIVED action")
    void on_clientArchived_records_audit() {
        ClientArchivedEvent event = new ClientArchivedEvent(CLIENT_ID, "Alice", ACTOR_ID, TENANT, NOW);

        listener.on(event);

        verify(auditPort).record(
                eq(ACTOR_ID),
                eq(TENANT),
                eq("CLIENT_ARCHIVED"),
                eq("Client"),
                eq(CLIENT_ID),
                eq(null),
                any()
        );
    }

    // ── SupplierCreatedEvent ──────────────────────────────────────────────────

    @Test
    @DisplayName("on(SupplierCreatedEvent) calls auditPort.record with SUPPLIER_CREATED action")
    void on_supplierCreated_records_audit() {
        SupplierCreatedEvent event = new SupplierCreatedEvent(SUPPLIER_ID, "Fournisseur SA", ACTOR_ID, TENANT, NOW);

        listener.on(event);

        verify(auditPort).record(
                eq(ACTOR_ID),
                eq(TENANT),
                eq("SUPPLIER_CREATED"),
                eq("Supplier"),
                eq(SUPPLIER_ID),
                eq(null),
                any()
        );
    }

    // ── SupplierArchivedEvent ─────────────────────────────────────────────────

    @Test
    @DisplayName("on(SupplierArchivedEvent) calls auditPort.record with SUPPLIER_ARCHIVED action")
    void on_supplierArchived_records_audit() {
        SupplierArchivedEvent event = new SupplierArchivedEvent(SUPPLIER_ID, "Fournisseur SA", ACTOR_ID, TENANT, NOW);

        listener.on(event);

        verify(auditPort).record(
                eq(ACTOR_ID),
                eq(TENANT),
                eq("SUPPLIER_ARCHIVED"),
                eq("Supplier"),
                eq(SUPPLIER_ID),
                eq(null),
                any()
        );
    }
}
