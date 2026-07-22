package com.keevo.catalog.product.domain.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.AuditEventListener;
import org.junit.jupiter.api.AfterEach;
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
 * ProductAuditEventTest — TDD tests for Product audit events integration.
 *
 * <p>Tests that ProductCreatedEvent, ProductUpdatedEvent, and ProductArchivedEvent 
 * are properly handled by AuditEventListener following Story 1.8 patterns.
 * 
 * <p>RED → GREEN:
 * - RED: AuditEventListener doesn't handle Product events yet
 * - GREEN: After adding Product event handlers to AuditEventListener
 * 
 * <p>Story 2.1 — Product CRUD with audit trail integration.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Product Audit Events")
class ProductAuditEventTest {

    @Mock
    AuditPort auditPort;

    ObjectMapper objectMapper;

    AuditEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new AuditEventListener(auditPort, objectMapper);
        TenantContext.clear(); // ensure clean thread state before each test
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear(); // always clean up thread state
    }

    // ── ProductCreatedEvent ───────────────────────────────────────────────────

    @Test
    @DisplayName("on(ProductCreatedEvent) calls auditPort.record() with PRODUCT_CREATED action")
    void onProductCreated_callsAuditPort() {
        // Given
        UUID productId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_abc123";
        
        ProductCreatedEvent event = new ProductCreatedEvent(
            productId,
            "Test Product",
            "KEV-TST123",
            tenantId,
            actorId,
            "OWNER",        // Story 14.10: actorRole
            "John",         // Story 14.10: actorName
            "Test Store",   // Story 14.10: storeName
            Instant.now()
        );

        // When
        listener.on(event);

        // Then - verify auditPort.record() called with correct parameters
        verify(auditPort).record(
                eq(actorId),              // actorId
                eq(tenantId),             // tenantId
                eq("PRODUCT_CREATED"),    // action
                eq("Product"),            // entityType
                eq(productId),            // entityId
                eq(null),                 // valueBefore — null for creation
                any()                     // valueAfter — JSON with product details
        );
    }

    // ── ProductUpdatedEvent ───────────────────────────────────────────────────

    @Test
    @DisplayName("on(ProductUpdatedEvent) calls auditPort.record() with PRODUCT_UPDATED action")
    void onProductUpdated_callsAuditPort() {
        // Given
        UUID productId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_abc123";
        String valueBefore = "{\"name\":\"Old Name\",\"sku\":\"KEV-OLD123\"}";
        String valueAfter = "{\"name\":\"New Name\",\"sku\":\"KEV-NEW123\"}";
        
        ProductUpdatedEvent event = new ProductUpdatedEvent(
            productId,
            "New Name",
            valueBefore,
            valueAfter,
            tenantId,
            actorId,
            Instant.now()
        );

        // When
        listener.on(event);

        // Then - verify auditPort.record() with valueBefore/valueAfter from event
        verify(auditPort).record(
                eq(actorId),              // actorId
                eq(tenantId),             // tenantId
                eq("PRODUCT_UPDATED"),    // action
                eq("Product"),            // entityType
                eq(productId),            // entityId
                eq(valueBefore),          // valueBefore — from event
                eq(valueAfter)            // valueAfter — from event
        );
    }

    // ── ProductArchivedEvent ──────────────────────────────────────────────────

    @Test
    @DisplayName("on(ProductArchivedEvent) calls auditPort.record() with PRODUCT_ARCHIVED action")
    void onProductArchived_callsAuditPort() {
        // Given
        UUID productId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_abc123";
        
        ProductArchivedEvent event = new ProductArchivedEvent(
            productId,
            "Archived Product",
            "KEV-ARC123",
            tenantId,
            actorId,
            Instant.now()
        );

        // When
        listener.on(event);

        // Then - verify auditPort.record() called with correct parameters
        verify(auditPort).record(
                eq(actorId),              // actorId
                eq(tenantId),             // tenantId
                eq("PRODUCT_ARCHIVED"),   // action
                eq("Product"),            // entityType
                eq(productId),            // entityId
                eq(null),                 // valueBefore — null for archive (just status change)
                any()                     // valueAfter — JSON with archive details
        );
    }

    // Note: Since Product endpoints are authenticated (@PreAuthorize("hasRole('USER')")), 
    // JwtAuthFilter has already set TenantContext before these events are published.
    // Therefore, NO manual TenantContext management is needed (unlike UserRegisteredEvent).

    // ── SalePriceOverriddenEvent ──────────────────────────────────────────────

    @Test
    @DisplayName("on(SalePriceOverriddenEvent) calls auditPort.record() with PRICE_OVERRIDDEN action")
    void onSalePriceOverridden_callsAuditPort() {
        // Given
        UUID productId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_abc123";

        SalePriceOverriddenEvent event = new SalePriceOverriddenEvent(
                productId,
                saleId,
                "Produit Test",  // productName (Story 4.2)
                5000,  // cataloguePrice
                4500,  // appliedPrice (overridden)
                actorId,
                tenantId,
                Instant.now()
        );

        // When
        listener.on(event);

        // Then
        verify(auditPort).record(
                eq(actorId),
                eq(tenantId),
                eq("PRICE_OVERRIDDEN"),
                eq("PriceOverride"),
                eq(productId),
                any(),   // valueBefore — JSON with cataloguePrice
                any()    // valueAfter  — JSON with appliedPrice
        );
    }
}