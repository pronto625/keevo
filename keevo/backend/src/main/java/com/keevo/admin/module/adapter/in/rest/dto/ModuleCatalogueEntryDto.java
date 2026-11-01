package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleManifest;

import java.util.List;

/**
 * One installed module of the catalogue (Story 13.8a, {@code GET /api/v1/admin/modules}); Story 16.4:
 * {@code migrations} = whether it ships tables the console can provision for every tenant.
 */
public record ModuleCatalogueEntryDto(String moduleId, String displayName, String category, boolean free,
                                      String version, List<String> requires, boolean migrations) {

    public static ModuleCatalogueEntryDto from(ModuleManifest manifest, boolean migrations) {
        return new ModuleCatalogueEntryDto(manifest.id(), manifest.displayName(), manifest.category().code(),
                manifest.free(), manifest.version(), manifest.requires(), migrations);
    }
}
