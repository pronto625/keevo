package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.CatalogImportPort;
import com.keevo.catalog.product.domain.port.out.CatalogImportWriter;
import com.keevo.catalog.product.domain.port.out.CatalogImportWriter.NewCategory;
import com.keevo.catalog.product.domain.service.ProductSkus;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.3 — implements {@link CatalogImportPort}: categories seeded by name (the onboarding seeder's rule:
 * trimmed, case-insensitive, first spelling kept, existing root names skipped), products inserted in one statement
 * with SKUs from {@link ProductSkus} (one uniqueness query). Joins the caller's transaction; the tenant context must be
 * the explicit schema (fails fast otherwise).
 */
@Component
class CatalogImportApiAdapter implements CatalogImportPort {

    private final CatalogImportWriter writer;

    CatalogImportApiAdapter(CatalogImportWriter writer) {
        this.writer = writer;
    }

    @Override
    public ImportedCategories seedImportCategories(String tenantSchema, List<String> templateNames,
                                                   String importedName) {
        TenantContext.requireCurrent(tenantSchema);
        String imported = importedName == null ? null : importedName.strip();
        if (imported == null || imported.isEmpty()) {
            throw new IllegalArgumentException("imported category name required");
        }
        Map<String, String> wanted = new LinkedHashMap<>(); // lower name → spelling
        for (String name : templateNames == null ? List.<String>of() : templateNames) {
            if (name != null && !name.isBlank()) {
                wanted.putIfAbsent(lower(name), name.strip());
            }
        }
        wanted.putIfAbsent(lower(imported), imported);
        Map<String, UUID> existing = writer.findRootCategories(tenantSchema, wanted.keySet());
        List<NewCategory> toCreate = new ArrayList<>();
        UUID importedId = existing.get(lower(imported));
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            if (existing.containsKey(e.getKey())) {
                continue;
            }
            boolean isImported = e.getKey().equals(lower(imported));
            UUID id = UUID.randomUUID();
            toCreate.add(new NewCategory(id, e.getValue(), isImported));
            if (isImported) {
                importedId = id;
            }
        }
        Set<UUID> created = writer.insertCategories(tenantSchema, toCreate);
        return new ImportedCategories(importedId, created.size());
    }

    @Override
    public Set<UUID> importProducts(String tenantSchema, List<ImportedProduct> products) {
        TenantContext.requireCurrent(tenantSchema);
        if (products == null || products.isEmpty()) {
            return Set.of();
        }
        List<String> skus = ProductSkus.unique(products.size(), c -> writer.existingSkus(tenantSchema, c));
        return writer.insertProducts(tenantSchema, products, skus);
    }

    @Override
    public Set<String> productNames(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return writer.productNames(tenantSchema);
    }

    @Override
    public Set<UUID> existingProducts(String tenantSchema, Collection<UUID> ids) {
        TenantContext.requireCurrent(tenantSchema);
        return ids == null || ids.isEmpty() ? Set.of() : writer.existingProducts(tenantSchema, ids);
    }

    private static String lower(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
