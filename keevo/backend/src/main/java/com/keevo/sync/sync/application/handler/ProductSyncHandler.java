package com.keevo.sync.sync.application.handler;

import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.catalog.api.CreateDraftProductInput;
import com.keevo.catalog.api.CreateProductInput;
import com.keevo.catalog.api.ProductCommandPort;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.UpdateProductInput;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ProductSyncHandler extends AbstractSyncOperationHandler {

    public static final String MERGED_INTO_PREFIX = "MERGED_INTO:";

    /** Story 17.13 — additive {@code conflictData} of a merge that restored an archived product. */
    public static final Map<String, Object> RESTORED_MARKER = Map.of("restored", true);

    /** Story 17.13 — same, for an archived draft restored as a draft (the device maps its product to a draft). */
    public static final Map<String, Object> RESTORED_DRAFT_MARKER = Map.of("restored", true, "status", "DRAFT");

    // 12.2a: catalog reached only through its public API.
    private final ProductCommandPort productCommand;
    private final ProductQueryPort productQuery;

    public ProductSyncHandler(ProductCommandPort productCommand, ProductQueryPort productQuery) {
        this.productCommand = productCommand;
        this.productQuery = productQuery;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("CREATE_PRODUCT", "CREATE_DRAFT_PRODUCT", "UPDATE_PRODUCT", "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT", "PROMOTE_PRODUCT");
    }

    @Override
    public String moduleId() {
        return "catalog";
    }

    @Override
    protected void validate(SyncOperation operation) {
        // Minimal validation — domain services handle deeper checks
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        Map<String, Object> p = operation.payload();
        return switch (operation.operationType()) {
            case "CREATE_PRODUCT" -> {
                // Preserve the client-generated id (local_product_datasource.dart sends "id")
                // so subsequent offline ops (RECORD_STOCK_ENTRY, CREATE_SALE, ...) that
                // reference this product by its local id keep resolving after push.
                UUID clientId = p.get("id") != null ? UUID.fromString((String) p.get("id")) : null;
                // 17.12: a product without stock tracking is created by the OWNER only (the input carries no role)
                Boolean trackStock = optionalBoolean(p.get("trackStock"), "trackStock");
                if (Boolean.FALSE.equals(trackStock) && !isOwnerRole()) {
                    throw new DomainException(ErrorCode.FORBIDDEN,
                            "Seul le propriétaire peut créer un produit sans suivi de stock");
                }
                var merged = mergeIntoExistingByName(operation, (String) p.get("name"), clientId, tenantId,
                        actorId, false);
                if (merged != null) yield merged;
                var product = productCommand.create(tenantId, new CreateProductInput(
                        clientId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        (String) p.get("sku"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("price"), "price"),
                        optionalIntegralInt(p.get("buyPrice"), "buyPrice"),
                        optionalIntegralInt(p.get("transportCost"), "transportCost"),
                        optionalIntegralInt(p.get("stockQuantity"), "stockQuantity"),
                        actorId,
                        trackStock));  // 17.12: absent (V1) → tracked
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        product.id().toString(), null);
            }
            case "UPDATE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                var product = productCommand.update(tenantId, new UpdateProductInput(
                        productId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        (String) p.get("sku"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("price"), "price"),
                        optionalIntegralInt(p.get("buyPrice"), "buyPrice"),
                        optionalIntegralInt(p.get("transportCost"), "transportCost"),
                        optionalIntegralInt(p.get("stockQuantity"), "stockQuantity"),
                        actorId,
                        actorRole(),  // v1s-12-10: real role → Story 2.4 draft guard fires for EMPLOYEE
                        optionalBoolean(p.get("trackStock"), "trackStock")));  // 17.12: absent → unchanged; change OWNER-only
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        product.id().toString(), null);
            }
            case "ARCHIVE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                productCommand.archive(tenantId, productId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        productId.toString(), null);
            }
            case "UNARCHIVE_PRODUCT" -> {
                UUID productId = UUID.fromString((String) p.get("productId"));
                productCommand.unarchive(tenantId, productId, actorId);
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        productId.toString(), null);
            }
            case "CREATE_DRAFT_PRODUCT" -> {
                // Create draft with the client-specified UUID so subsequent ops (CREATE_SALE,
                // VALIDATE_SALE) can reference the same ID without remapping.
                UUID clientId = p.get("id") != null ? UUID.fromString((String) p.get("id")) : null;
                var merged = mergeIntoExistingByName(operation, (String) p.get("name"), clientId, tenantId,
                        actorId, true);
                if (merged != null) yield merged;
                var draft = productCommand.createDraft(tenantId, new CreateDraftProductInput(
                        clientId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("priceVente"), "priceVente"),
                        null,  // buyPrice
                        null,  // transportCost
                        actorId,
                        actorRole(),  // v1s-12-10: real role instead of hardcoded "OWNER"
                        "Sync"));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        draft.id().toString(), null);
            }
            case "PROMOTE_PRODUCT" -> {
                // Promote the EXISTING draft product (by its local UUID) to ACTIVE.
                // Uses updateProduct so DRAFT→ACTIVE logic and cascade events fire correctly.
                // v1s-12-10: promotion is OWNER-only (Story 2.4 AC6) — reject before any lookup.
                if (!isOwnerRole()) {
                    throw new DomainException(ErrorCode.FORBIDDEN,
                            "Seul le propriétaire peut valider un produit en brouillon");
                }
                UUID productId = UUID.fromString((String) p.get("productId"));
                var promoted = productCommand.update(tenantId, new UpdateProductInput(
                        productId,
                        (String) p.get("name"),
                        (String) p.get("description"),
                        null,  // sku — keep existing
                        p.get("categoryId") != null ? UUID.fromString((String) p.get("categoryId")) : null,
                        optionalIntegralInt(p.get("price"), "price"),
                        optionalIntegralInt(p.get("buyPrice"), "buyPrice"),
                        optionalIntegralInt(p.get("transportCost"), "transportCost"),
                        null,  // stockQuantity — keep existing
                        actorId,
                        "OWNER"));
                yield new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                        promoted.id().toString(), null);
            }
            default -> new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED,
                    null, "UNKNOWN_OPERATION_TYPE");
        };
    }

    /**
     * A product with this name already exists (same case-insensitive name, different id): skip the
     * creation and answer APPLIED against the existing product instead of failing on uq_products_name.
     * The client-generated id is recorded as an alias via the {@code MERGED_INTO:<serverId>} reason
     * (see {@code SyncPushService}) so the stock entries / sales queued against that local id land
     * on the existing product. Checked BEFORE calling the creation use cases: letting them throw
     * PRODUCT_NAME_ALREADY_EXISTS would mark the surrounding sync transaction rollback-only.
     *
     * <p>Story 17.13 — the existing product E is ARCHIVED:
     * <ul>
     *   <li>{@code CREATE_PRODUCT}: E is restored through the @Transactional 17.10 unarchive (product-limit check under
     *   the same per-tenant lock, « Produit désarchivé » audit), keeps its fields, and the merge carries
     *   {@link #RESTORED_MARKER} (plus {@code "status": "DRAFT"} for an archived draft, restored as a draft). At the
     *   plan limit the unarchive throws {@code PLAN_LIMIT_EXCEEDED}: that marks the sync transaction rollback-only, and
     *   the rejection keeps its reason and 16.14 details through {@code SyncPushService}'s rollback path; E stays
     *   archived and no alias is recorded (the queued stock entry answers {@code PRODUCT_NOT_FOUND}). E untracked and
     *   the actor not the OWNER → {@code FORBIDDEN} (17.12: untracked products are the owner's).</li>
     *   <li>{@code CREATE_DRAFT_PRODUCT} (POS): E is restored when allowed ({@code unarchiveIfAllowed}, never throws
     *   at the limit); refused (limit, or untracked E for a non-owner) → plain merge without marker, E stays archived,
     *   so the queued sale is never lost.</li>
     * </ul>
     * The marker is set only when a restore really happened (a concurrent restore makes the unarchive a no-op).
     */
    private SyncOperationResult mergeIntoExistingByName(SyncOperation operation, String name, UUID clientId,
                                                         String tenantId, UUID actorId, boolean draftCreation) {
        if (name == null || name.isBlank()) return null;
        var existing = productQuery.findByName(tenantId, name);
        if (existing.isEmpty() || existing.get().id().equals(clientId)) return null;
        var target = existing.get();
        String targetId = target.id().toString();
        if (target.archived()) {
            boolean ownerOnly = !target.trackStock() && !isOwnerRole();
            if (ownerOnly && !draftCreation) {
                throw new DomainException(ErrorCode.FORBIDDEN,
                        "Seul le propriétaire peut créer un produit sans suivi de stock");
            }
            if (!ownerOnly) {
                boolean restored = draftCreation
                        ? productCommand.unarchiveIfAllowed(tenantId, target.id(), actorId)
                        : productCommand.unarchive(tenantId, target.id(), actorId);
                if (restored) {
                    return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                            targetId, MERGED_INTO_PREFIX + targetId, restoredMarker(target));
                }
            }
        }
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                targetId, MERGED_INTO_PREFIX + targetId);
    }

    private static Map<String, Object> restoredMarker(com.keevo.catalog.api.ProductView restored) {
        return restored.status() == com.keevo.catalog.api.ProductState.DRAFT ? RESTORED_DRAFT_MARKER : RESTORED_MARKER;
    }

    /** Story 17.12 — an optional JSON boolean; anything else is rejected ({@code Invalid <field>: …}). */
    static Boolean optionalBoolean(Object raw, String field) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException("Invalid " + field + ": must be a boolean");
    }
}
