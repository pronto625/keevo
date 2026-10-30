package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleManifest;

import java.util.List;

/** One installed module of the catalogue (Story 13.8a, {@code GET /api/v1/admin/modules}). */
public record ModuleCatalogueEntryDto(String moduleId, String displayName, String category, boolean free,
                                      String version, List<String> requires) {

    public static ModuleCatalogueEntryDto from(ModuleManifest manifest) {
        return new ModuleCatalogueEntryDto(manifest.id(), manifest.displayName(), manifest.category().code(),
                manifest.free(), manifest.version(), manifest.requires());
    }
}
