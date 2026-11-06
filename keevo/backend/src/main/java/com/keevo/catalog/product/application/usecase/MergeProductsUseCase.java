package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.api.ProductStockPresence;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.catalog.product.domain.service.ProductKeys;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.product.ProductMergedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MergeProductsUseCase — Story 20.1 (Bref convention: the path product is merged AWAY into the target).
 *
 * <p>Command in one transaction. Both rows are locked {@code FOR UPDATE} in id order (two crossed merges cannot
 * deadlock), then checked: same product → 422; unknown → 404; source or target already merged, or target archived →
 * 409 {@code PRODUCT_MERGE_INVALID}; source holding stock in any store or with a transfer in transit
 * ({@link ProductStockPresence}, inventory, optional — its stock rows are locked first) → 409. Effect, three updates: the source is archived and points at the target; products already merged
 * into the source are re-pointed to the target (one UPDATE — the redirection map stays flat, every lookup is one
 * hop); the target inherits the source barcode when it has none (key recomputed). Sale lines and movements keep their
 * product id and name snapshot. Audited « Produit fusionné ».
 */
@Service
public class MergeProductsUseCase {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<ProductStockPresence> stockPresence; // optional — the catalogue starts without stock

    public MergeProductsUseCase(ProductRepository productRepository,
                                ApplicationEventPublisher eventPublisher,
                                ObjectProvider<ProductStockPresence> stockPresence) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
        this.stockPresence = stockPresence;
    }

    /** @return the target product after the merge */
    @Transactional
    public Product execute(UUID sourceId, UUID targetId, UUID actorId) {
        if (sourceId == null || targetId == null) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Choisissez le produit à conserver");
        }
        if (sourceId.equals(targetId)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                    "Un produit ne peut pas être fusionné avec lui-même");
        }
        List<Product> locked = productRepository.lockForMerge(List.of(sourceId, targetId));
        Product source = find(locked, sourceId);
        Product target = find(locked, targetId);

        if (source.isMerged()) {
            throw invalid("Ce produit a déjà été fusionné avec un autre produit");
        }
        if (target.isMerged()) {
            throw invalid("« " + target.getName() + " » a déjà été fusionné avec un autre produit");
        }
        if (Boolean.TRUE.equals(target.getArchived())) {
            throw invalid("« " + target.getName() + " » est archivé : désarchivez-le avant de fusionner");
        }
        String tenant = TenantContext.getCurrentTenant();
        ProductStockPresence presence = stockPresence.getIfAvailable();
        ProductStockPresence.MergeBlocker blocker = presence == null ? ProductStockPresence.MergeBlocker.NONE
                : presence.lockAndCheckMergeable(tenant, sourceId);
        if (blocker == ProductStockPresence.MergeBlocker.STOCK) {
            throw invalid("Ce produit a encore du stock : mettez-le à zéro avant de le fusionner");
        }
        if (blocker == ProductStockPresence.MergeBlocker.TRANSFER_IN_TRANSIT) {
            throw invalid("Ce produit a un transfert en cours : terminez-le avant de le fusionner");
        }

        Instant now = Instant.now();
        productRepository.markMerged(sourceId, targetId, now);
        productRepository.repointMerged(sourceId, targetId, now);
        if (target.getBarcode() == null && source.getBarcode() != null) {
            productRepository.updateBarcode(targetId, source.getBarcode(),
                    ProductKeys.key(target.getName(), source.getBarcode()), now);
        }

        eventPublisher.publishEvent(new ProductMergedEvent(
                sourceId, source.getName(), targetId, target.getName(), tenant, actorId, now));

        return productRepository.findById(targetId)
                .orElseThrow(() -> new DomainException(ErrorCode.PRODUCT_NOT_FOUND, "Produit introuvable"));
    }

    private static Product find(List<Product> locked, UUID id) {
        return locked.stream().filter(p -> id.equals(p.getId())).findFirst()
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.PRODUCT_NOT_FOUND, "Produit introuvable"));
    }

    private static DomainException invalid(String reason) {
        return DomainException.userFacing(ErrorCode.PRODUCT_MERGE_INVALID, reason);
    }
}
