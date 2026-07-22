package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import com.keevo.identity.onboarding.domain.model.OnboardingCompletedEvent;
import com.keevo.catalog.contact.domain.event.ClientArchivedEvent;
import com.keevo.catalog.contact.domain.event.ClientCreatedEvent;
import com.keevo.catalog.contact.domain.event.SupplierArchivedEvent;
import com.keevo.catalog.contact.domain.event.SupplierCreatedEvent;
import com.keevo.catalog.product.domain.event.ProductCreatedEvent;
import com.keevo.catalog.product.domain.event.ProductUpdatedEvent;
import com.keevo.catalog.product.domain.event.ProductArchivedEvent;
import com.keevo.catalog.product.domain.event.ProductActivatedEvent;
import com.keevo.catalog.product.domain.event.SalePriceOverriddenEvent;
import com.keevo.catalog.stock.domain.event.StockAdjustedEvent;
import com.keevo.catalog.stock.domain.event.StockThresholdBreachedEvent;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.inventory.counting.domain.event.InventoryCountSavedEvent;
import com.keevo.inventory.counting.domain.event.InventoryReportGeneratedEvent;
import com.keevo.inventory.counting.domain.event.InventorySessionCancelledEvent;
import com.keevo.inventory.counting.domain.event.InventorySessionCreatedEvent;
import com.keevo.inventory.counting.domain.event.InventoryValidatedEvent;
import com.keevo.store.store.domain.event.StoreCreatedEvent;
import com.keevo.store.store.domain.event.StoreDeactivatedEvent;
import com.keevo.store.store.domain.event.StoreUpdatedEvent;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

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

    // ── Product Events (Story 2.1) ───────────────────────────────────────────

    /**
     * Handle product created event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(ProductCreatedEvent event) {
        // Authenticated endpoint — JwtAuthFilter already set TenantContext; NO manual set/clear
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "PRODUCT_CREATED",
                "Product",
                event.productId(),
                null,
                toJson(Map.of(
                        "productName", event.productName(),
                        "productSku",  event.productSku()
                ))
        );
        log.info("AUDIT: product_created productId={} productName={} tenantId={} actorId={}",
                event.productId(), event.productName(), event.tenantId(), event.actorId());
    }

    /**
     * Handle product updated event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(ProductUpdatedEvent event) {
        // Authenticated endpoint — JwtAuthFilter already set TenantContext; NO manual set/clear
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "PRODUCT_UPDATED",
                "Product",
                event.productId(),
                event.valueBefore(),  // JSON from event
                event.valueAfter()    // JSON from event
        );
        log.info("AUDIT: product_updated productId={} productName={} tenantId={} actorId={}",
                event.productId(), event.productName(), event.tenantId(), event.actorId());
    }

    /**
     * Handle product archived event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(ProductArchivedEvent event) {
        // Authenticated endpoint — JwtAuthFilter already set TenantContext; NO manual set/clear
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "PRODUCT_ARCHIVED",
                "Product",
                event.productId(),
                null,
                toJson(Map.of(
                        "productName", event.productName(),
                        "productSku",  event.productSku(),
                        "archived",     true
                ))
        );
        log.info("AUDIT: product_archived productId={} productName={} tenantId={} actorId={}",
                event.productId(), event.productName(), event.tenantId(), event.actorId());
    }

    /**
     * Handle sale price override event (Epic 4 — POS).
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     *
     * <p>Records the override in the audit log with entityType="PriceOverride" so that
     * compliance queries can filter by entity type.
     */
    @EventListener
    public void on(SalePriceOverriddenEvent event) {
        // Authenticated endpoint — JwtAuthFilter already set TenantContext; NO manual set/clear
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "PRICE_OVERRIDDEN",
                "PriceOverride",
                event.productId(),
                toJson(Map.of("cataloguePrice", event.cataloguePrice())),
                toJson(Map.of(
                        "appliedPrice", event.appliedPrice(),
                        "productName",  event.productName() != null ? event.productName() : "",
                        "saleId",       event.saleId() != null ? event.saleId().toString() : null,
                        "occurredAt",   event.occurredAt().toString()
                ))
        );
        log.info("AUDIT: price_overridden productId={} cataloguePrice={} appliedPrice={} tenantId={} actorId={}",
                event.productId(), event.cataloguePrice(), event.appliedPrice(),
                event.tenantId(), event.actorId());
    }

    // ── Stock Events (Story 2.3) ──────────────────────────────────────────────

    /**
     * Handle stock adjusted event — records every stock movement in the audit log.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management.
     */
    @EventListener
    public void on(StockAdjustedEvent event) {
        auditPort.record(
            event.actorId(),
            event.tenantId(),
            "STOCK_ADJUSTED",
            "StockMovement",
            event.productId(),
            toJson(Map.of("quantityBefore", event.quantityBefore())),
            toJson(Map.of(
                "movementType",   event.movementType().name(),
                "quantityChange", event.quantityChange(),
                "quantityAfter",  event.quantityAfter(),
                "storeId",        event.storeId().toString(),
                "notes",          event.notes() != null ? event.notes() : ""
            ))
        );
        log.info("AUDIT: stock_adjusted productId={} type={} before={} change={} after={} tenantId={}",
            event.productId(), event.movementType(), event.quantityBefore(),
            event.quantityChange(), event.quantityAfter(), event.tenantId());
    }

    /**
     * Handle stock threshold breached event — signals low-stock alert in audit log.
     *
     * <p>FCM push notification delivery deferred to Story 8-1.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management.
     */
    @EventListener
    public void on(StockThresholdBreachedEvent event) {
        auditPort.record(
            event.actorId(),
            event.tenantId(),
            "STOCK_THRESHOLD_BREACHED",
            "StockAlert",
            event.productId(),
            null,
            toJson(Map.of(
                "productName",      event.productName(),
                "storeId",          event.storeId().toString(),
                "currentQuantity",  event.currentQuantity(),
                "threshold",        event.threshold()
            ))
        );
        log.warn("AUDIT: stock_threshold_breached productId={} product={} qty={} threshold={} tenantId={}",
            event.productId(), event.productName(), event.currentQuantity(),
            event.threshold(), event.tenantId());
    }

    // ── Contact Events (Story 2.5) ────────────────────────────────────────────

    /**
     * Handle client created event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management.
     */
    @EventListener
    public void on(ClientCreatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "CLIENT_CREATED",
                "Client",
                event.clientId(),
                null,
                toJson(Map.of("name", event.name()))
        );
        log.info("AUDIT: client_created clientId={} tenantId={} actorId={}",
                event.clientId(), event.tenantId(), event.actorId());
    }

    /**
     * Handle client archived event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management.
     */
    @EventListener
    public void on(ClientArchivedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "CLIENT_ARCHIVED",
                "Client",
                event.clientId(),
                null,
                toJson(Map.of("name", event.name(), "archived", true))
        );
        log.info("AUDIT: client_archived clientId={} tenantId={} actorId={}",
                event.clientId(), event.tenantId(), event.actorId());
    }

    /**
     * Handle supplier created event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management.
     */
    @EventListener
    public void on(SupplierCreatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "SUPPLIER_CREATED",
                "Supplier",
                event.supplierId(),
                null,
                toJson(Map.of("name", event.name()))
        );
        log.info("AUDIT: supplier_created supplierId={} tenantId={} actorId={}",
                event.supplierId(), event.tenantId(), event.actorId());
    }

    /**
     * Handle supplier archived event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management.
     */
    @EventListener
    public void on(SupplierArchivedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "SUPPLIER_ARCHIVED",
                "Supplier",
                event.supplierId(),
                null,
                toJson(Map.of("name", event.name(), "archived", true))
        );
        log.info("AUDIT: supplier_archived supplierId={} tenantId={} actorId={}",
                event.supplierId(), event.tenantId(), event.actorId());
    }

    // ── Store Events (Story 3.1) ──────────────────────────────────────────────

    /**
     * Handle store created event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(StoreCreatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "STORE_CREATED",
                "Store",
                event.storeId(),
                null,
                toJson(Map.of("name", event.name(), "type", event.type().name()))
        );
        log.info("AUDIT: store_created storeId={} name={} type={} tenantId={} actorId={}",
                event.storeId(), event.name(), event.type(), event.tenantId(), event.actorId());
    }

    /**
     * Handle store updated event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(StoreUpdatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "STORE_UPDATED",
                "Store",
                event.storeId(),
                null,
                toJson(Map.of("newName", event.newName()))
        );
        log.info("AUDIT: store_updated storeId={} newName={} tenantId={} actorId={}",
                event.storeId(), event.newName(), event.tenantId(), event.actorId());
    }

    /**
     * Handle store deactivated event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(StoreDeactivatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "STORE_DEACTIVATED",
                "Store",
                event.storeId(),
                null,
                toJson(Map.of("name", event.name(), "deactivated", true))
        );
        log.info("AUDIT: store_deactivated storeId={} name={} tenantId={} actorId={}",
                event.storeId(), event.name(), event.tenantId(), event.actorId());
    }

    // ── Sale events (Story 4.1) ──────────────────────────────────────────────

    /**
     * Handle sale completed event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(SaleCompletedEvent event) {
        auditPort.record(
                event.getActorId(),
                event.getTenantId(),
                "SALE_COMPLETED",
                "Sale",
                event.getSaleId(),
                null,
                toJson(Map.of(
                        "storeId",        event.getStoreId().toString(),
                        "totalAmount",    event.getTotalAmount(),
                        "discountAmount", event.getDiscountAmount(),
                        "itemsSnapshot",  event.getItemsSnapshot()
                ))
        );
        log.info("AUDIT: sale_completed saleId={} storeId={} total={} discount={} tenantId={} actorId={}",
                event.getSaleId(), event.getStoreId(), event.getTotalAmount(),
                event.getDiscountAmount(), event.getTenantId(), event.getActorId());
    }

    // ── Story 4.3 — Pending validation events ───────────────────────────────────

    @EventListener
    public void on(ProductActivatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "PRODUCT_ACTIVATED",
                "Product",
                event.productId(),
                null,
                toJson(Map.of("productId", event.productId().toString()))
        );
        log.info("AUDIT: product_activated productId={} tenantId={} actorId={}",
                event.productId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(SalePendingValidationEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "SALE_PENDING_VALIDATION",
                "Sale",
                event.saleId(),
                null,
                toJson(Map.of(
                        "storeId", event.storeId().toString(),
                        "draftProductIds", event.draftProductIds().stream().map(UUID::toString).toList(),
                        "totalAmount", event.totalAmount()
                ))
        );
        log.info("AUDIT: sale_pending_validation saleId={} tenantId={} actorId={}",
                event.saleId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(SaleAutoValidatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "SALE_AUTO_VALIDATED",
                "Sale",
                event.saleId(),
                null,
                toJson(Map.of("triggerProductId", event.triggerProductId().toString()))
        );
        log.info("AUDIT: sale_auto_validated saleId={} triggeredBy={} tenantId={}",
                event.saleId(), event.triggerProductId(), event.tenantId());
    }

    @EventListener
    public void on(SaleManuallyValidatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "SALE_MANUALLY_VALIDATED",
                "Sale",
                event.saleId(),
                null,
                toJson(Map.of(
                        "justification", event.justification(),
                        "forcedProducts", event.forcedProducts().stream().map(UUID::toString).toList()
                ))
        );
        log.info("AUDIT: sale_manually_validated saleId={} forcedProducts={} tenantId={}",
                event.saleId(), event.forcedProducts().size(), event.tenantId());
    }

    @EventListener
    public void on(SaleCancelledEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "SALE_CANCELLED",
                "Sale",
                event.saleId(),
                null,
                toJson(Map.of(
                        "justification", event.justification() != null ? event.justification() : "",
                        "itemsSnapshot", event.itemsSnapshot()
                ))
        );
        log.info("AUDIT: sale_cancelled saleId={} tenantId={} actorId={}",
                event.saleId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(SaleCorrectedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "SALE_CORRECTED",
                "Sale",
                event.saleId(),
                null,
                toJson(Map.of(
                        "justification", event.justification(),
                        "beforeSnapshot", event.beforeSnapshot(),
                        "afterSnapshot", event.afterSnapshot()
                ))
        );
        log.info("AUDIT: sale_corrected saleId={} tenantId={} actorId={}",
                event.saleId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(StockForcedZeroEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "STOCK_FORCED_ZERO",
                "StockLevel",
                event.productId(),
                null,
                toJson(Map.of(
                        "storeId", event.storeId().toString(),
                        "requestedQuantity", event.requestedQuantity(),
                        "availableQuantity", event.availableQuantity()
                ))
        );
        log.info("AUDIT: stock_forced_zero productId={} storeId={} requested={} available={}",
                event.productId(), event.storeId(), event.requestedQuantity(), event.availableQuantity());
    }

    // ── Day Closure Events (Story 4.4) ───────────────────────────────────────

    /**
     * Handle day closed event — records the day closure in the audit log.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(DayClosedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "DAY_CLOSED",
                "DayClosure",
                event.closureId(),
                null,
                toJson(Map.of(
                        "storeId", event.storeId().toString(),
                        "isAutomatic", event.isAutomatic(),
                        "totalSales", event.summary().totalSales(),
                        "totalRevenue", event.summary().totalRevenue(),
                        "occurredAt", event.occurredAt().toString()
                ))
        );
        log.info("AUDIT: day_closed closureId={} storeId={} isAutomatic={} totalSales={} totalRevenue={} tenantId={} actorId={}",
                event.closureId(), event.storeId(), event.isAutomatic(),
                event.summary().totalSales(), event.summary().totalRevenue(),
                event.tenantId(), event.actorId());
    }

    // ── Inventory (Story 6.1) ─────────────────────────────────────────────────

    /**
     * Handle inventory session created event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(InventorySessionCreatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "INVENTORY_SESSION_CREATED",
                "InventorySession",
                event.sessionId(),
                null,
                toJson(Map.of(
                        "storeId", event.storeId().toString(),
                        "scope", event.scope().name(),
                        "occurredAt", event.occurredAt().toString()
                ))
        );
        log.info("AUDIT: inventory_session_created sessionId={} storeId={} scope={} tenantId={} actorId={}",
                event.sessionId(), event.storeId(), event.scope(),
                event.tenantId(), event.actorId());
    }

    /**
     * Handle inventory session cancelled event.
     *
     * <p><b>AUTHENTICATED endpoint</b> — JwtAuthFilter already set TenantContext → NO manual management needed.
     */
    @EventListener
    public void on(InventorySessionCancelledEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "INVENTORY_SESSION_CANCELLED",
                "InventorySession",
                event.sessionId(),
                null,
                toJson(Map.of(
                        "cancelledBy", event.actorId().toString(),
                        "occurredAt", event.occurredAt().toString()
                ))
        );
        log.info("AUDIT: inventory_session_cancelled sessionId={} tenantId={} actorId={}",
                event.sessionId(), event.tenantId(), event.actorId());
    }

    // ── Inventory Count (Story 6.2) ──────────────────────────────────────────

    @EventListener
    public void on(InventoryCountSavedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "INVENTORY_COUNT_SAVED",
                "InventoryCount",
                event.countId(),
                null,
                toJson(Map.of(
                        "sessionId", event.sessionId().toString(),
                        "productId", event.productId().toString(),
                        "theoretical", event.theoretical(),
                        "physical", event.physical(),
                        "ecart", event.ecart(),
                        "occurredAt", event.occurredAt().toString()
                ))
        );
        log.info("AUDIT: inventory_count_saved countId={} sessionId={} productId={} ecart={} tenantId={}",
                event.countId(), event.sessionId(), event.productId(), event.ecart(), event.tenantId());
    }

    // ── Inventory Report (Story 6.3) ────────────────────────────────────────

    @EventListener
    public void on(InventoryReportGeneratedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "INVENTORY_REPORT_GENERATED",
                "InventorySession",
                event.sessionId(),
                null,
                toJson(Map.of(
                        "sessionId", event.sessionId().toString(),
                        "occurredAt", event.occurredAt().toString()
                ))
        );
        log.info("AUDIT: inventory_report_generated sessionId={} tenantId={} actorId={}",
                event.sessionId(), event.tenantId(), event.actorId());
    }

    // ── Inventory Validation (Story 6.4) ────────────────────────────────────────

    @EventListener
    public void on(InventoryValidatedEvent event) {
        auditPort.record(
                event.actorId(),
                event.tenantId(),
                "INVENTORY_VALIDATED",
                "InventorySession",
                event.sessionId(),
                null,
                toJson(Map.of(
                        "adjustmentCount", event.adjustmentCount(),
                        "occurredAt", event.occurredAt().toString()
                ))
        );
        log.info("AUDIT: inventory_validated sessionId={} adjustments={} tenantId={} actorId={}",
                event.sessionId(), event.adjustmentCount(), event.tenantId(), event.actorId());
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
