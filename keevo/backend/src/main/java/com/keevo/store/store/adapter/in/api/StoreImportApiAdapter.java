package com.keevo.store.store.adapter.in.api;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreCountry;
import com.keevo.store.api.StoreImportPort;
import com.keevo.store.api.StoreProfilePatch;
import com.keevo.store.store.domain.model.StoreFactory;
import com.keevo.store.store.domain.model.StoreProfileRules;
import com.keevo.store.store.domain.port.in.StoreLogoUseCase;
import com.keevo.store.store.domain.port.out.StoreImportWriter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — implements {@link StoreImportPort}: every store normalized by the store rules (name 2–100, Bref fields)
 * before one batch insert; the logo through {@link StoreLogoUseCase}. Joins the caller's transaction; the tenant
 * context must be the explicit schema (fails fast otherwise).
 */
@Component
class StoreImportApiAdapter implements StoreImportPort {

    private final StoreImportWriter writer;
    private final StoreLogoUseCase logos;

    StoreImportApiAdapter(StoreImportWriter writer, StoreLogoUseCase logos) {
        this.writer = writer;
        this.logos = logos;
    }

    @Override
    public Set<UUID> importStores(String tenantSchema, List<ImportedStore> stores) {
        TenantContext.requireCurrent(tenantSchema);
        if (stores == null || stores.isEmpty()) {
            return Set.of();
        }
        List<ImportedStore> normalized = stores.stream().map(StoreImportApiAdapter::normalize).toList();
        return writer.insertAbsent(tenantSchema, normalized);
    }

    @Override
    public void importLogo(String tenantSchema, UUID storeId, byte[] image, UUID actorId) {
        TenantContext.requireCurrent(tenantSchema);
        logos.upload(storeId, image, actorId);
    }

    /** The store rules of the REST creation: trimmed name, category key, bounded fields, upper-case country. */
    static ImportedStore normalize(ImportedStore s) {
        String name = StoreFactory.validateName(s.name());
        StoreProfilePatch p = StoreProfileRules.normalize(new StoreProfilePatch(s.category(), s.description(),
                s.niu(), s.rccm(), s.country() == null ? StoreCountry.DEFAULT : s.country()));
        return new ImportedStore(s.id(), name, blankToNull(s.address()), blankToNull(s.phone()),
                blankToNull(p.category()), blankToNull(p.description()), blankToNull(p.niu()), blankToNull(p.rccm()),
                p.country() == null ? StoreCountry.DEFAULT : p.country(), s.active(), s.createdAt(), s.updatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
