package com.keevo.catalog.product.application.usecase;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LookupProductByBarcodeUseCase — Story 20.1, {@code GET /api/v1/products/lookup?barcode=}: the visible product a
 * scanned or typed code stands for.
 *
 * <p>One indexed query ({@code barcode = code OR product_key = 'bc:'||code}, partial indexes, merged rows included,
 * direct matches first) plus one query for the merge targets of the merged candidates, one hop through {@code merged_into_id} (merge chains are flat). The first match
 * whose product — itself, or its merge target — is not archived wins; none → 404 « Aucun produit avec ce code ».
 */
@Service
public class LookupProductByBarcodeUseCase {

    /** Candidates read at most — a code shared by more products than this is a data problem, not a lookup. */
    static final int MAX_CANDIDATES = 20;

    private final ProductRepository productRepository;

    public LookupProductByBarcodeUseCase(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public Product execute(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Saisissez un code-barres ou une référence");
        }
        List<Product> candidates = productRepository.findByBarcodeOrKey(code, "bc:" + code, MAX_CANDIDATES);
        // the merge targets of every merged candidate in one query (≤ 2 queries in all)
        List<UUID> targetIds = candidates.stream().filter(Product::isMerged).map(Product::getMergedIntoId)
                .distinct().toList();
        Map<UUID, Product> targets = targetIds.isEmpty() ? Map.of()
                : productRepository.findAllByIds(targetIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
        for (Product candidate : candidates) {
            Product resolved = candidate.isMerged() ? targets.get(candidate.getMergedIntoId()) : candidate;
            if (resolved != null && !Boolean.TRUE.equals(resolved.getArchived())) {
                return resolved;
            }
        }
        throw DomainException.userFacing(ErrorCode.PRODUCT_NOT_FOUND, "Aucun produit avec ce code");
    }
}
