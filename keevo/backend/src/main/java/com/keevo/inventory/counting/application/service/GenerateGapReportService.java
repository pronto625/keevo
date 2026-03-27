package com.keevo.inventory.counting.application.service;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.port.out.ProductRepository;
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
import com.keevo.store.store.domain.port.out.StoreRepository;
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
    private final ProductRepository productRepo;
    private final StoreRepository storeRepo;

    public GenerateGapReportService(InventorySessionRepository sessionRepo,
                                     InventoryCountRepository countRepo,
                                     ProductRepository productRepo,
                                     StoreRepository storeRepo) {
        this.sessionRepo = sessionRepo;
        this.countRepo = countRepo;
        this.productRepo = productRepo;
        this.storeRepo = storeRepo;
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
        Map<UUID, Product> productsMap = productRepo.findAllByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // Step 4: Get store name
        String storeName = storeRepo.findById(session.getStoreId())
                .map(s -> s.name()).orElse("Boutique inconnue");

        // Step 5: Build report using Builder pattern
        InventoryGapReportBuilder builder = new InventoryGapReportBuilder()
                .sessionId(session.getId())
                .storeId(session.getStoreId())
                .storeName(storeName)
                .scope(session.getScope())
                .generatedBy(query.actorId());

        for (InventoryCount count : counts) {
            if (!count.isCounted()) continue; // skip uncounted
            Product product = productsMap.get(count.getProductId());
            int unitPrice = product != null ? product.getPriceValue() : 0;
            builder.addRow(count, unitPrice);
        }

        // Step 5b: Enrich with product details (sku, photoUrl)
        Map<UUID, InventoryGapReportBuilder.ProductDetails> details = productsMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> new InventoryGapReportBuilder.ProductDetails(
                                e.getValue().getSku(), null, null)));
        builder.enrichProductDetails(details);

        return builder.build();
    }
}
