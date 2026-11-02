package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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

    public CreateOrRestoreProductUseCase(CreateProductUseCase createProductUseCase,
                                         UnarchiveProductUseCase unarchiveProductUseCase,
                                         ProductRepository productRepository,
                                         TransactionLockPort transactionLock) {
        this.createProductUseCase = createProductUseCase;
        this.unarchiveProductUseCase = unarchiveProductUseCase;
        this.productRepository = productRepository;
        this.transactionLock = transactionLock;
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
     *   <li>E restored concurrently meanwhile (unarchive no-op): a draft gets E as merged, an active creation falls
     *   through to the normal creation (409 for the now-visible name).</li>
     * </ul>
     */
    @Transactional
    public Outcome execute(CreateProductUseCase.CreateProductDto dto) {
        transactionLock.lock("product-limit:" + TenantContext.getCurrentTenant());
        if (dto.name() != null && !dto.name().isBlank()) {
            var existing = productRepository.findByName(dto.name());
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
