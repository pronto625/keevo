package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.model.AccountDeletionCancelledEvent;
import com.keevo.identity.auth.domain.model.AccountDeletionRequestedEvent;
import com.keevo.identity.auth.domain.model.UserAuthenticatedEvent;
import com.keevo.identity.auth.domain.model.UserRegisteredEvent;
import com.keevo.identity.auth.domain.model.PasswordResetRequestedEvent;
import com.keevo.identity.auth.domain.model.PasswordResetEvent;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.onboarding.domain.model.OnboardingCompletedEvent;
import com.keevo.catalog.category.domain.event.CategoryActiveChangedEvent;
import com.keevo.catalog.category.domain.event.CategoryCreatedEvent;
import com.keevo.catalog.category.domain.event.CategoryRenamedEvent;
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
import com.keevo.reporting.export.domain.event.DataExportedEvent;
import com.keevo.store.store.domain.event.StoreCreatedEvent;
import com.keevo.store.store.domain.event.StoreDeactivatedEvent;
import com.keevo.store.store.domain.event.StoreUpdatedEvent;
import com.keevo.identity.employee.domain.event.EmployeeUpdatedEvent;
import com.keevo.identity.employee.domain.event.EmployeeRoleChangedEvent;
import com.keevo.identity.employee.domain.event.EmployeePasswordSetByOwnerEvent;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p><b>Story v1s-18-6 — enrichment.</b> Every handler writes an {@link AuditPort.AuditEntry} with:
 * <ul>
 *   <li>{@code storeId} — the event's store (Store events: the store itself), else the actor's
 *       assigned store resolved at write time;</li>
 *   <li>{@code entityLabel} — readable target name at action time, {@code null} when the event
 *       carries none (never a UUID);</li>
 *   <li>{@code source}/{@code deviceId} — from {@link AuditWriteContextResolver}
 *       (ONLINE / SYNC / SYSTEM);</li>
 *   <li>{@code valueBefore}/{@code valueAfter} — entity fields only; keys read by the shipped app
 *       stay in {@code valueAfter};</li>
 *   <li>{@code metadata} — pure context (store id, notes, justification, occurredAt, sale id,
 *       scope, trigger ids, ...) plus {@code actorLabel}.</li>
 * </ul>
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    static final String LABEL_SALE = "Vente";
    static final String LABEL_DAY_CLOSURE = "Clôture de journée";
    static final String LABEL_INVENTORY = "Inventaire";

    private final AuditPort auditPort;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final AuditWriteContextResolver contextResolver;

    @Autowired
    public AuditEventListener(AuditPort auditPort, ObjectMapper objectMapper,
                              UserRepository userRepository,
                              AuditWriteContextResolver contextResolver) {
        this.auditPort       = auditPort;
        this.objectMapper    = objectMapper;
        this.userRepository  = userRepository;
        this.contextResolver = contextResolver;
    }

    /** Convenience for unit tests — uses a stateless resolver. */
    public AuditEventListener(AuditPort auditPort, ObjectMapper objectMapper,
                              UserRepository userRepository) {
        this(auditPort, objectMapper, userRepository, new AuditWriteContextResolver());
    }

    // ── Identity ──────────────────────────────────────────────────────────────

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
            write(event.userId(), event.schemaName(), "USER_REGISTERED", "User", event.userId(),
                    null,
                    map("tenantCode", event.tenantCode(), "schemaName", event.schemaName()),
                    null, null, null, null);
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
            write(event.userId(), event.tenantId(), "USER_AUTHENTICATED", "User", event.userId(),
                    null,
                    map("role", event.role()),
                    null, null,
                    map("ip", event.ipAddress() != null ? event.ipAddress() : "unknown"),
                    null);
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
        write(event.actorId(), event.tenantId(), "ONBOARDING_COMPLETED", "Tenant", event.actorId(),
                null,
                map("sectorType", event.sectorType().name(),
                        "storeName", event.storeName(),
                        "categoriesCreated", event.categoriesCreated()),
                null, event.storeName(), null, null);
        log.info("AUDIT: onboarding_completed tenantId={} sector={} storeName={} categoriesCreated={} actorId={}",
                event.tenantId(), event.sectorType(), event.storeName(),
                event.categoriesCreated(), event.actorId());
    }

    // ── Product Events (Story 2.1) ───────────────────────────────────────────

    @EventListener
    public void on(ProductCreatedEvent event) {
        write(event.actorId(), event.tenantId(), "PRODUCT_CREATED", "Product", event.productId(),
                null,
                map("productName", event.productName(), "productSku", event.productSku()),
                null, event.productName(), null, event.actorName());
        log.info("AUDIT: product_created productId={} productName={} tenantId={} actorId={}",
                event.productId(), event.productName(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(ProductUpdatedEvent event) {
        writeRaw(event.actorId(), event.tenantId(), "PRODUCT_UPDATED", "Product", event.productId(),
                event.valueBefore(),  // JSON from event (field-by-field)
                event.valueAfter(),   // JSON from event
                null, event.productName(), null, null);
        log.info("AUDIT: product_updated productId={} productName={} tenantId={} actorId={}",
                event.productId(), event.productName(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(ProductArchivedEvent event) {
        write(event.actorId(), event.tenantId(), "PRODUCT_ARCHIVED", "Product", event.productId(),
                null,
                map("productName", event.productName(), "productSku", event.productSku(), "archived", true),
                null, event.productName(), null, null);
        log.info("AUDIT: product_archived productId={} productName={} tenantId={} actorId={}",
                event.productId(), event.productName(), event.tenantId(), event.actorId());
    }

    /**
     * Handle sale price override event (Epic 4 — POS). entityType="PriceOverride" so that
     * compliance queries can filter by entity type.
     */
    @EventListener
    public void on(SalePriceOverriddenEvent event) {
        write(event.actorId(), event.tenantId(), "PRICE_OVERRIDDEN", "PriceOverride", event.productId(),
                map("cataloguePrice", event.cataloguePrice()),
                map("appliedPrice", event.appliedPrice(),
                        "productName", event.productName() != null ? event.productName() : ""),
                null, event.productName(),
                map("saleId", event.saleId() != null ? event.saleId().toString() : null,
                        "occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: price_overridden productId={} cataloguePrice={} appliedPrice={} tenantId={} actorId={}",
                event.productId(), event.cataloguePrice(), event.appliedPrice(),
                event.tenantId(), event.actorId());
    }

    // ── Stock Events (Story 2.3) ──────────────────────────────────────────────

    @EventListener
    public void on(StockAdjustedEvent event) {
        write(event.actorId(), event.tenantId(), "STOCK_ADJUSTED", "StockMovement", event.productId(),
                map("quantityBefore", event.quantityBefore()),
                map("movementType", event.movementType().name(),
                        "quantityChange", event.quantityChange(),
                        "quantityAfter", event.quantityAfter()),
                event.storeId(), event.productName(),
                map("storeId", str(event.storeId()),
                        "variantId", str(event.variantId()),
                        "notes", event.notes()),
                event.actorName());
        log.info("AUDIT: stock_adjusted productId={} type={} before={} change={} after={} tenantId={}",
                event.productId(), event.movementType(), event.quantityBefore(),
                event.quantityChange(), event.quantityAfter(), event.tenantId());
    }

    @EventListener
    public void on(StockThresholdBreachedEvent event) {
        write(event.actorId(), event.tenantId(), "STOCK_THRESHOLD_BREACHED", "StockAlert", event.productId(),
                null,
                map("productName", event.productName(),
                        "currentQuantity", event.currentQuantity(),
                        "threshold", event.threshold()),
                event.storeId(), event.productName(),
                map("storeId", str(event.storeId())),
                null);
        log.warn("AUDIT: stock_threshold_breached productId={} product={} qty={} threshold={} tenantId={}",
                event.productId(), event.productName(), event.currentQuantity(),
                event.threshold(), event.tenantId());
    }

    // ── Contact Events (Story 2.5) ────────────────────────────────────────────

    @EventListener
    public void on(ClientCreatedEvent event) {
        write(event.actorId(), event.tenantId(), "CLIENT_CREATED", "Client", event.clientId(),
                null, map("name", event.name()), null, event.name(), null, null);
        log.info("AUDIT: client_created clientId={} tenantId={} actorId={}",
                event.clientId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(ClientArchivedEvent event) {
        write(event.actorId(), event.tenantId(), "CLIENT_ARCHIVED", "Client", event.clientId(),
                null, map("name", event.name(), "archived", true), null, event.name(), null, null);
        log.info("AUDIT: client_archived clientId={} tenantId={} actorId={}",
                event.clientId(), event.tenantId(), event.actorId());
    }

    /** v1s-14-15 — REST create or sync CREATE_CATEGORY. */
    @EventListener
    public void on(CategoryCreatedEvent event) {
        write(event.actorId(), event.tenantId(), "CATEGORY_CREATED", "Category", event.categoryId(),
                null,
                map("name", event.name(),
                        "parentId", event.parentId() != null ? event.parentId().toString() : null),
                null, event.name(), null, null);
        log.info("AUDIT: category_created categoryId={} tenantId={} actorId={}",
                event.categoryId(), event.tenantId(), event.actorId());
    }

    /** v1s-14-15 — toggle (REST/sync) or REST delete. */
    @EventListener
    public void on(CategoryActiveChangedEvent event) {
        write(event.actorId(), event.tenantId(),
                event.active() ? "CATEGORY_ACTIVATED" : "CATEGORY_DEACTIVATED",
                "Category", event.categoryId(),
                map("active", !event.active()),
                map("name", event.name(), "active", event.active()),
                null, event.name(), null, null);
        log.info("AUDIT: category_{} categoryId={} tenantId={} actorId={}",
                event.active() ? "activated" : "deactivated",
                event.categoryId(), event.tenantId(), event.actorId());
    }

    /** v1s-14-15 — REST rename or sync RENAME_CATEGORY. */
    @EventListener
    public void on(CategoryRenamedEvent event) {
        write(event.actorId(), event.tenantId(), "CATEGORY_RENAMED", "Category", event.categoryId(),
                map("name", event.oldName()),
                map("name", event.newName()),
                null, event.newName(), null, null);
        log.info("AUDIT: category_renamed categoryId={} tenantId={} actorId={}",
                event.categoryId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(SupplierCreatedEvent event) {
        write(event.actorId(), event.tenantId(), "SUPPLIER_CREATED", "Supplier", event.supplierId(),
                null, map("name", event.name()), null, event.name(), null, null);
        log.info("AUDIT: supplier_created supplierId={} tenantId={} actorId={}",
                event.supplierId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(SupplierArchivedEvent event) {
        write(event.actorId(), event.tenantId(), "SUPPLIER_ARCHIVED", "Supplier", event.supplierId(),
                null, map("name", event.name(), "archived", true), null, event.name(), null, null);
        log.info("AUDIT: supplier_archived supplierId={} tenantId={} actorId={}",
                event.supplierId(), event.tenantId(), event.actorId());
    }

    // ── Store Events (Story 3.1) — store column = the store itself ───────────

    @EventListener
    public void on(StoreCreatedEvent event) {
        write(event.actorId(), event.tenantId(), "STORE_CREATED", "Store", event.storeId(),
                null, map("name", event.name(), "type", event.type().name()),
                event.storeId(), event.name(), null, null);
        log.info("AUDIT: store_created storeId={} name={} type={} tenantId={} actorId={}",
                event.storeId(), event.name(), event.type(), event.tenantId(), event.actorId());
    }

    /** The event carries no previous name, so {@code valueBefore} stays null. */
    @EventListener
    public void on(StoreUpdatedEvent event) {
        write(event.actorId(), event.tenantId(), "STORE_UPDATED", "Store", event.storeId(),
                null, map("newName", event.newName(), "name", event.newName()),
                event.storeId(), event.newName(), null, null);
        log.info("AUDIT: store_updated storeId={} newName={} tenantId={} actorId={}",
                event.storeId(), event.newName(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(StoreDeactivatedEvent event) {
        write(event.actorId(), event.tenantId(), "STORE_DEACTIVATED", "Store", event.storeId(),
                null, map("name", event.name(), "deactivated", true),
                event.storeId(), event.name(), null, null);
        log.info("AUDIT: store_deactivated storeId={} name={} tenantId={} actorId={}",
                event.storeId(), event.name(), event.tenantId(), event.actorId());
    }

    // ── Employee events (Story 14.11) — events carry no name → label null ────

    @EventListener
    public void on(EmployeeUpdatedEvent event) {
        write(event.actorId(), event.tenantId(), "EMPLOYEE_UPDATED", "Employee", event.employeeId(),
                null, map("fieldsChanged", event.fieldsChanged()), null, null, null, null);
        log.info("AUDIT: employee_updated employeeId={} fieldsChanged={} tenantId={} actorId={}",
                event.employeeId(), event.fieldsChanged(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(EmployeeRoleChangedEvent event) {
        write(event.actorId(), event.tenantId(), "EMPLOYEE_ROLE_CHANGED", "Employee", event.employeeId(),
                map("role", event.previousRole()),
                map("role", event.newRole()),
                null, null, null, null);
        log.info("AUDIT: employee_role_changed employeeId={} {}→{} tenantId={} actorId={}",
                event.employeeId(), event.previousRole(), event.newRole(),
                event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(EmployeePasswordSetByOwnerEvent event) {
        write(event.actorId(), event.tenantId(), "EMPLOYEE_PASSWORD_SET_BY_OWNER", "Employee",
                event.employeeId(),
                null, map("forcedReset", true), null, null, null, null);
        log.info("AUDIT: employee_password_set_by_owner employeeId={} tenantId={} actorId={}",
                event.employeeId(), event.tenantId(), event.actorId());
    }

    // ── Account deletion events (Story 14.5, FR91) ──────────────────────────

    @EventListener
    public void on(AccountDeletionRequestedEvent event) {
        write(event.actorId(), event.tenantId().toString(), "ACCOUNT_DELETION_REQUESTED", "Tenant",
                event.tenantId(),
                null, map("scheduledDeletionAt", str(event.scheduledDeletionAt())),
                null, null, null, null);
        log.info("AUDIT: account_deletion_requested tenantId={} actorId={} scheduledDeletion={}",
                event.tenantId(), event.actorId(), event.scheduledDeletionAt());
    }

    @EventListener
    public void on(AccountDeletionCancelledEvent event) {
        write(event.actorId(), event.tenantId().toString(), "ACCOUNT_DELETION_CANCELLED", "Tenant",
                event.tenantId(),
                null, map("cancelledAt", str(event.cancelledAt())),
                null, null, null, null);
        log.info("AUDIT: account_deletion_cancelled tenantId={} actorId={}",
                event.tenantId(), event.actorId());
    }

    // ── Sale events (Story 4.1 / 4.3) ────────────────────────────────────────

    @EventListener
    public void on(SaleCompletedEvent event) {
        write(event.getActorId(), event.getTenantId(), "SALE_COMPLETED", "Sale", event.getSaleId(),
                null,
                map("totalAmount", event.getTotalAmount(),
                        "discountAmount", event.getDiscountAmount(),
                        "itemsSnapshot", event.getItemsSnapshot()),
                event.getStoreId(), LABEL_SALE,
                map("storeId", str(event.getStoreId()), "occurredAt", str(event.getOccurredAt())),
                null);
        log.info("AUDIT: sale_completed saleId={} storeId={} total={} discount={} tenantId={} actorId={}",
                event.getSaleId(), event.getStoreId(), event.getTotalAmount(),
                event.getDiscountAmount(), event.getTenantId(), event.getActorId());
    }

    @EventListener
    public void on(ProductActivatedEvent event) {
        write(event.actorId(), event.tenantId(), "PRODUCT_ACTIVATED", "Product", event.productId(),
                map("status", "DRAFT"), map("status", "ACTIVE"),
                null, null, null, null);
        log.info("AUDIT: product_activated productId={} tenantId={} actorId={}",
                event.productId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(SalePendingValidationEvent event) {
        write(event.actorId(), event.tenantId(), "SALE_PENDING_VALIDATION", "Sale", event.saleId(),
                null,
                map("status", "PENDING_VALIDATION",
                        "draftProductIds", event.draftProductIds().stream().map(UUID::toString).toList(),
                        "totalAmount", event.totalAmount()),
                event.storeId(), LABEL_SALE,
                map("storeId", str(event.storeId())),
                null);
        log.info("AUDIT: sale_pending_validation saleId={} tenantId={} actorId={}",
                event.saleId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(SaleAutoValidatedEvent event) {
        write(event.actorId(), event.tenantId(), "SALE_AUTO_VALIDATED", "Sale", event.saleId(),
                map("status", "PENDING_VALIDATION"), map("status", "COMPLETED"),
                event.storeId(), LABEL_SALE,
                map("triggerProductId", str(event.triggerProductId())),
                null);
        log.info("AUDIT: sale_auto_validated saleId={} triggeredBy={} tenantId={}",
                event.saleId(), event.triggerProductId(), event.tenantId());
    }

    @EventListener
    public void on(SaleManuallyValidatedEvent event) {
        write(event.actorId(), event.tenantId(), "SALE_MANUALLY_VALIDATED", "Sale", event.saleId(),
                map("status", "PENDING_VALIDATION"), map("status", "COMPLETED"),
                event.storeId(), LABEL_SALE,
                map("justification", event.justification(),
                        "forcedProducts", event.forcedProducts().stream().map(UUID::toString).toList()),
                null);
        log.info("AUDIT: sale_manually_validated saleId={} forcedProducts={} tenantId={}",
                event.saleId(), event.forcedProducts().size(), event.tenantId());
    }

    @EventListener
    public void on(SaleCancelledEvent event) {
        write(event.actorId(), event.tenantId(), "SALE_CANCELLED", "Sale", event.saleId(),
                null,
                map("status", "CANCELLED", "itemsSnapshot", event.itemsSnapshot()),
                event.storeId(), LABEL_SALE,
                map("justification", event.justification() != null ? event.justification() : ""),
                null);
        log.info("AUDIT: sale_cancelled saleId={} tenantId={} actorId={}",
                event.saleId(), event.tenantId(), event.actorId());
    }

    /** Field-by-field: before/after are the sale item snapshots; justification is context. */
    @EventListener
    public void on(SaleCorrectedEvent event) {
        writeRaw(event.actorId(), event.tenantId(), "SALE_CORRECTED", "Sale", event.saleId(),
                event.beforeSnapshot(), event.afterSnapshot(),
                event.storeId(), LABEL_SALE,
                map("justification", event.justification()),
                null);
        log.info("AUDIT: sale_corrected saleId={} tenantId={} actorId={}",
                event.saleId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(StockForcedZeroEvent event) {
        write(event.actorId(), event.tenantId(), "STOCK_FORCED_ZERO", "StockLevel", event.productId(),
                null,
                map("requestedQuantity", event.requestedQuantity(),
                        "availableQuantity", event.availableQuantity()),
                event.storeId(), null,
                map("storeId", str(event.storeId())),
                null);
        log.info("AUDIT: stock_forced_zero productId={} storeId={} requested={} available={}",
                event.productId(), event.storeId(), event.requestedQuantity(), event.availableQuantity());
    }

    // ── Day Closure Events (Story 4.4) ───────────────────────────────────────

    @EventListener
    public void on(DayClosedEvent event) {
        write(event.actorId(), event.tenantId(), "DAY_CLOSED", "DayClosure", event.closureId(),
                null,
                map("totalSales", event.summary().totalSales(),
                        "totalRevenue", event.summary().totalRevenue()),
                event.storeId(), LABEL_DAY_CLOSURE,
                map("storeId", str(event.storeId()),
                        "isAutomatic", event.isAutomatic(),
                        "occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: day_closed closureId={} storeId={} isAutomatic={} totalSales={} totalRevenue={} tenantId={} actorId={}",
                event.closureId(), event.storeId(), event.isAutomatic(),
                event.summary().totalSales(), event.summary().totalRevenue(),
                event.tenantId(), event.actorId());
    }

    // ── Inventory (Story 6.1 – 6.4) ─────────────────────────────────────────

    @EventListener
    public void on(InventorySessionCreatedEvent event) {
        write(event.actorId(), event.tenantId(), "INVENTORY_SESSION_CREATED", "InventorySession",
                event.sessionId(),
                null, map("status", "IN_PROGRESS"),
                event.storeId(), LABEL_INVENTORY,
                map("storeId", str(event.storeId()),
                        "scope", event.scope().name(),
                        "occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: inventory_session_created sessionId={} storeId={} scope={} tenantId={} actorId={}",
                event.sessionId(), event.storeId(), event.scope(),
                event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(InventorySessionCancelledEvent event) {
        write(event.actorId(), event.tenantId(), "INVENTORY_SESSION_CANCELLED", "InventorySession",
                event.sessionId(),
                map("status", "IN_PROGRESS"), map("status", "CANCELLED"),
                event.storeId(), LABEL_INVENTORY,
                map("cancelledBy", str(event.actorId()), "occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: inventory_session_cancelled sessionId={} tenantId={} actorId={}",
                event.sessionId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(InventoryCountSavedEvent event) {
        write(event.actorId(), event.tenantId(), "INVENTORY_COUNT_SAVED", "InventoryCount",
                event.countId(),
                null,
                map("productId", str(event.productId()),
                        "variantId", str(event.variantId()),
                        "theoretical", event.theoretical(),
                        "physical", event.physical(),
                        "ecart", event.ecart()),
                event.storeId(), null,
                map("sessionId", str(event.sessionId()), "occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: inventory_count_saved countId={} sessionId={} productId={} ecart={} tenantId={}",
                event.countId(), event.sessionId(), event.productId(), event.ecart(), event.tenantId());
    }

    @EventListener
    public void on(InventoryReportGeneratedEvent event) {
        write(event.actorId(), event.tenantId(), "INVENTORY_REPORT_GENERATED", "InventorySession",
                event.sessionId(),
                null, null,
                event.storeId(), LABEL_INVENTORY,
                map("occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: inventory_report_generated sessionId={} tenantId={} actorId={}",
                event.sessionId(), event.tenantId(), event.actorId());
    }

    @EventListener
    public void on(InventoryValidatedEvent event) {
        write(event.actorId(), event.tenantId(), "INVENTORY_VALIDATED", "InventorySession",
                event.sessionId(),
                map("status", "IN_PROGRESS"),
                map("status", "VALIDATED", "adjustmentCount", event.adjustmentCount()),
                event.storeId(), LABEL_INVENTORY,
                map("occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: inventory_validated sessionId={} adjustments={} tenantId={} actorId={}",
                event.sessionId(), event.adjustmentCount(), event.tenantId(), event.actorId());
    }

    // ── Export (Story v1s-18-1) ──────────────────────────────────────────────

    /**
     * Every accepted export (direct or background), published on the request thread so the entry
     * keeps source ONLINE, the actor label and the device.
     */
    @EventListener
    public void on(DataExportedEvent event) {
        List<String> names = event.datasetLabels().size() == event.datasets().size()
                ? event.datasetLabels() : event.datasets(); // French labels, keys as fallback
        String label = "Export " + event.format() + " — " + String.join(", ", names);
        write(event.actorId(), event.tenantId(), "DATA_EXPORTED", "Export", event.exportId(),
                null,
                map("format", event.format(),
                        "datasets", event.datasets(),
                        "storeId", str(event.storeId()),
                        "from", str(event.from()),
                        "to", str(event.to()),
                        "criteria", new java.util.TreeMap<>(event.criteria()),
                        "rowCount", event.rowCount(),
                        "mode", event.mode()),
                event.storeId(), label,
                map("occurredAt", str(event.occurredAt())),
                null);
        log.info("AUDIT: data_exported exportId={} format={} datasets={} rows={} mode={} tenantId={} actorId={}",
                event.exportId(), event.format(), event.datasets(), event.rowCount(), event.mode(),
                event.tenantId(), event.actorId());
    }

    // ── Password reset (Story 14.12) — cross-tenant, best-effort ────────────

    /**
     * <b>PUBLIC endpoint</b> — /auth/forgot-password has no JWT, no TenantContext.
     * Audits in EVERY tenant where the user has a membership. Anti-PII: only userId is logged.
     */
    @EventListener
    public void on(PasswordResetRequestedEvent event) {
        auditInEveryMembership(event.userId(), "PASSWORD_RESET_REQUESTED", "password_reset_requested");
    }

    /** Same cross-tenant loop as {@link PasswordResetRequestedEvent}. */
    @EventListener
    public void on(PasswordResetEvent event) {
        auditInEveryMembership(event.userId(), "PASSWORD_RESET", "password_reset");
    }

    private void auditInEveryMembership(UUID userId, String action, String logName) {
        List<UserMembershipInfo> memberships = userRepository.findMembershipsWithTenantInfo(userId);
        for (UserMembershipInfo m : memberships) {
            TenantContext.setCurrentTenant(m.schemaName());
            try {
                write(userId, m.schemaName(), action, "User", userId,
                        null, map("userId", userId.toString()), null, null, null, null);
                log.info("AUDIT: {} userId={} schema={}", logName, userId, m.schemaName());
            } catch (Exception e) {
                // Best-effort audit per tenant — one tenant's audit failure must NOT
                // skip remaining tenants nor propagate to the caller (which would roll
                // back the outer @Transactional and fail the user's password reset).
                log.error("AUDIT FAILED: {} userId={} schema={} error={}",
                        logName, userId, m.schemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    // ── Template Method helpers ───────────────────────────────────────────────

    /**
     * Build and record one enriched entry (maps serialized to JSON).
     *
     * @param storeId        event store, or null → actor's assigned store (resolver fallback)
     * @param entityLabel    readable target label, or null
     * @param context        pure context for {@code metadata} (null values dropped)
     * @param eventActorName actor display name carried by the event; preferred over the
     *                       resolver label when non-blank
     */
    private void write(UUID actorId, String tenantId, String action, String entityType, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after,
                       UUID storeId, String entityLabel, Map<String, Object> context,
                       String eventActorName) {
        writeRaw(actorId, tenantId, action, entityType, entityId,
                before != null ? toJson(before) : null,
                after != null ? toJson(after) : null,
                storeId, entityLabel, context, eventActorName);
    }

    private void writeRaw(UUID actorId, String tenantId, String action, String entityType, UUID entityId,
                          String valueBefore, String valueAfter,
                          UUID storeId, String entityLabel, Map<String, Object> context,
                          String eventActorName) {
        AuditWriteContextResolver.WriteContext ctx = contextResolver.resolve();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context != null) {
            context.forEach((k, v) -> {
                if (v != null) metadata.put(k, v);
            });
        }
        // The event's real actor name beats the resolver label (a role fallback when no firstName).
        String eventName = blankToNull(eventActorName);
        String actorLabel = eventName != null ? eventName : ctx.actorLabel();
        if (actorLabel != null) metadata.put("actorLabel", actorLabel);

        auditPort.record(new AuditPort.AuditEntry(
                actorId, tenantId, action, entityType, entityId,
                valueBefore, valueAfter,
                storeId != null ? storeId : ctx.fallbackStoreId(),
                truncate(blankToNull(entityLabel), 200),
                ctx.deviceId(),
                ctx.source(),
                metadata.isEmpty() ? null : toJson(metadata)));
    }

    /** Insertion-ordered map that tolerates null values (unlike {@code Map.of}). */
    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Cut at {@code max} chars without splitting a surrogate pair. */
    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    /**
     * Serialize an object to a JSON string.
     * Returns "{}" on error — the entry is still written, the failure is logged as an error.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("AUDIT_SERIALIZATION_FAILED AuditEventListener: failed to serialize audit JSON — using {{}}: {}",
                    e.getMessage(), e);
            return "{}";
        }
    }
}
