package com.keevo.catalog.product.adapter.in.api;

import com.keevo.catalog.api.ProductLookupPort;
import com.keevo.catalog.product.application.usecase.DeriveProductsFromDocumentUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ProvidesCapability;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.6 — the {@link ProductLookupPort} capability of {@code catalog}, delegating to
 * {@link DeriveProductsFromDocumentUseCase}. Not an autowire candidate ({@link ProvidesCapability}): reachable only
 * through {@code CapabilityRegistry.find}, i.e. only while {@code catalog} is usable for the tenant.
 */
@Component
@ProvidesCapability(module = "catalog")
class ProductLookupAdapter implements ProductLookupPort {

    private final DeriveProductsFromDocumentUseCase derivation;

    ProductLookupAdapter(DeriveProductsFromDocumentUseCase derivation) {
        this.derivation = derivation;
    }

    @Override
    public Map<String, UUID> findOrCreateForDocument(String tenantSchema, List<DocumentLine> lines, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        return derivation.execute(lines, actorId, tenantSchema);
    }
}
