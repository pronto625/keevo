package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.catalog.api.InitialStockPort;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 17.13 — online product creation ({@code POST /api/v1/products}) whose name belongs to an ARCHIVED product:
 * the archived product is restored instead of answering 409 (the case-insensitive unique name index includes archived
 * rows, so a second product with that name can never exist). The restore is the 17.10 unarchive (product-limit check
 * for an ACTIVE product, none for a draft; « désarchivé » audit); the product keeps its existing fields — nothing of
 * the creation payload is applied. A name held by a visible product keeps the 409 {@code PRODUCT_NAME_ALREADY_EXISTS}.
 *
 * <p>The per-tenant {@code product-limit} lock is taken first (re-entrant: the creation and unarchive use cases take
 * it again in this same transaction), so the name lookup → restore / create cannot interleave with a concurrent
 * creation or restore.
 */
@Service
public class CreateOrRestoreProductUseCase {

    private final CreateProductUseCase createProductUseCase;
    private final UnarchiveProductUseCase unarchiveProductUseCase;
    private final ProductRepository productRepository;
    private final TransactionLockPort transactionLock;
    /** Story 22.19 (D11) — {@code null} (tests): no initial stock recorded. */
    private final ObjectProvider<InitialStockPort> initialStock;
    private final StoreQueryPort stores;

    public CreateOrRestoreProductUseCase(CreateProductUseCase createProductUseCase,
                                         UnarchiveProductUseCase unarchiveProductUseCase,
                                         ProductRepository productRepository,
                                         TransactionLockPort transactionLock) {
        this(createProductUseCase, unarchiveProductUseCase, productRepository, transactionLock, null, null);
    }

    @Autowired
    public CreateOrRestoreProductUseCase(CreateProductUseCase createProductUseCase,
                                         UnarchiveProductUseCase unarchiveProductUseCase,
                                         ProductRepository productRepository,
                                         TransactionLockPort transactionLock,
                                         ObjectProvider<InitialStockPort> initialStock,
                                         StoreQueryPort stores) {
        this.createProductUseCase = createProductUseCase;
        this.unarchiveProductUseCase = unarchiveProductUseCase;
        this.productRepository = productRepository;
        this.transactionLock = transactionLock;
        this.initialStock = initialStock;
        this.stores = stores;
    }

    /** Story 22.19 (D11) — the movement note of the initial quantity typed at creation. */
    public static final String INITIAL_STOCK_NOTE = "Stock initial";
    static final String INVALID_STORE = "Boutique inconnue ou fermée : choisissez une boutique active";

    /**
     * Story 22.19 (D11) — {@link #execute(CreateProductUseCase.CreateProductDto)}, then, for a product really created
     * (not restored nor merged), tracked and with {@code stockQuantity > 0}: that quantity enters a store as a stock
     * entry « Stock initial », in the same transaction — {@code storeId} (an unknown or inactive one → 422
     * {@code VALIDATION_ERROR} on {@code storeId}, nothing created), else the default store. Without a stock module or a store, nothing more than the legacy column.
     *
     * @param storeId the store asked by the client (owner); a store member's own store is passed by the caller
     */
    @Transactional
    public Outcome execute(CreateProductUseCase.CreateProductDto dto, java.util.UUID storeId) {
        Outcome outcome = execute(dto);
        Product created = outcome.product();
        if (outcome.restored() || outcome.merged() || initialStock == null || stores == null
                || !created.isTrackStock() || dto.stockQuantity() == null || dto.stockQuantity() <= 0) {
            return outcome;
        }
        InitialStockPort port = initialStock.getIfAvailable();
        String tenant = TenantContext.getCurrentTenant();
        java.util.Optional<java.util.UUID> target;
        if (storeId != null) {
            // Story 22.19 (D11) — an unknown or closed store is refused (the product is rolled back), never replaced
            target = stores.findById(tenant, storeId).filter(s -> s.active()).map(s -> s.id());
            if (target.isEmpty()) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, INVALID_STORE,
                        java.util.Map.of("field", "storeId", "reason", INVALID_STORE));
            }
        } else {
            target = stores.defaultStore(tenant);
        }
        if (port == null || target.isEmpty()) {
            return outcome;
        }
        port.recordInitialStock(tenant, created.getId(), target.get(), dto.stockQuantity(), dto.actorId(),
                INITIAL_STOCK_NOTE);
        return new Outcome(reload(created), false, false);
    }

    /**
     * The created product; or the archived product of that name, {@code restored} when it was actually restored, or
     * {@code merged} (draft creation whose restore was refused: returned as is, still archived — 17.13 fallback).
     */
    public record Outcome(Product product, boolean restored, boolean merged) {
        public Outcome(Product product, boolean restored) {
            this(product, restored, false);
        }
    }

    /**
     * Story 17.13 rules for a name held by an ARCHIVED product E (the client id may even be E's own id):
     * <ul>
     *   <li>ACTIVE creation: E restored (403 {@code PLAN_LIMIT_EXCEEDED} at the limit); E untracked and the actor not
     *   the OWNER → 403 {@code FORBIDDEN} (17.12).</li>
     *   <li>DRAFT creation ({@code status: DRAFT}, same rule as the sync {@code CREATE_DRAFT_PRODUCT}): E restored
     *   when allowed; refused (limit, or untracked E for a non-owner) → E returned unchanged ({@code merged}).</li>
     *   <li>Story 20.1 — E merged into T: T answered as is ({@code merged}, E stays archived); T archived itself →
     *   the rules above apply to T.</li>
     *   <li>E restored concurrently meanwhile (unarchive no-op): a draft gets E as merged, an active creation falls
     *   through to the normal creation (409 for the now-visible name).</li>
     * </ul>
     */
    @Transactional
    public Outcome execute(CreateProductUseCase.CreateProductDto dto) {
        transactionLock.lock("product-limit:" + TenantContext.getCurrentTenant());
        if (dto.name() != null && !dto.name().isBlank()) {
            var existing = productRepository.findByName(dto.name());
            // Story 20.1: a merged product is never resurrected — its name answers with the merge target
            if (existing.isPresent() && existing.get().isMerged()) {
                existing = productRepository.findById(existing.get().getMergedIntoId());
                if (existing.isPresent() && !Boolean.TRUE.equals(existing.get().getArchived())) {
                    return new Outcome(existing.get(), false, true);
                }
            }
            if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getArchived())) {
                Product archived = existing.get();
                boolean draft = "DRAFT".equalsIgnoreCase(dto.status());
                boolean ownerOnly = !archived.isTrackStock()
                        && dto.actorRole() != null && !"OWNER".equals(dto.actorRole());
                if (ownerOnly) {
                    if (draft) return new Outcome(archived, false, true);
                    throw new DomainException(ErrorCode.FORBIDDEN,
                            "Seul le propriétaire peut créer un produit sans suivi de stock");
                }
                var restoreDto = new UnarchiveProductUseCase.UnarchiveProductDto(archived.getId(), dto.actorId());
                boolean restored = draft
                        ? unarchiveProductUseCase.tryExecute(restoreDto)
                        : unarchiveProductUseCase.execute(restoreDto);
                if (restored) return new Outcome(reload(archived), true, false);
                if (draft) return new Outcome(reload(archived), false, true);
            }
        }
        return new Outcome(createProductUseCase.execute(dto), false);
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId())
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Produit introuvable"));
    }
}
