package com.keevo.inventory.counting.application.service;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductView;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryGapReport;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportQuery;
import com.keevo.inventory.counting.domain.port.in.GenerateGapReportUseCase;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.inventory.counting.domain.service.InventoryGapReportBuilder;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GenerateGapReportService — Template Method for gap report generation.
 *
 * <p>Fixed steps: load session → load counts → load products → load store → build report.
 * Story 6.3 — Gap Analysis Report.
 */
@Service
@Transactional(readOnly = true)
public class GenerateGapReportService implements GenerateGapReportUseCase {

    private final InventorySessionRepository sessionRepo;
    private final InventoryCountRepository countRepo;
    private final ProductQueryPort productQuery; // 12.2a: catalog public API
    private final StoreQueryPort storeQuery;

    public GenerateGapReportService(InventorySessionRepository sessionRepo,
                                     InventoryCountRepository countRepo,
                                     ProductQueryPort productQuery,
                                     StoreQueryPort storeQuery) {
        this.sessionRepo = sessionRepo;
        this.countRepo = countRepo;
        this.productQuery = productQuery;
        this.storeQuery = storeQuery;
    }

    @Override
    public InventoryGapReport execute(GenerateGapReportQuery query) {
        // Step 1: Load session
        InventorySession session = sessionRepo.findById(query.sessionId())
                .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND,
                        "Session not found: " + query.sessionId()));

        // Step 2: Load all counts for the session
        List<InventoryCount> counts = countRepo.findBySessionId(session.getId());

        // Step 3: Load product details for price enrichment
        List<UUID> productIds = counts.stream()
                .map(InventoryCount::getProductId).distinct().toList();
        Map<UUID, ProductView> productsMap = productQuery.findAllByIds(productIds).stream()
                .collect(Collectors.toMap(ProductView::id, Function.identity()));

        // Step 4: Get store name
        String storeName = storeQuery.findById(TenantContext.getCurrentTenant(), session.getStoreId())
                .map(s -> s.name()).orElse("Boutique inconnue");

        // Step 5: Build report using Builder pattern
        InventoryGapReportBuilder builder = new InventoryGapReportBuilder()
                .sessionId(session.getId())
                .storeId(session.getStoreId())
                .storeName(storeName)
                .scope(session.getScope())
                .generatedBy(query.actorId())
                .sessionStatus(session.getStatus().name());

        for (InventoryCount count : counts) {
            if (!count.isCounted()) continue; // skip uncounted
            ProductView product = productsMap.get(count.getProductId());
            int unitPrice = product != null ? product.price() : 0;
            builder.addRow(count, unitPrice);
        }

        // Step 5b: Enrich with product details (sku, photoUrl)
        Map<UUID, InventoryGapReportBuilder.ProductDetails> details = productsMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> new InventoryGapReportBuilder.ProductDetails(
                                e.getValue().sku(), null, null)));
        builder.enrichProductDetails(details);

        return builder.build();
    }
}
