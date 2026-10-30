package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleLifecycle;

/** A module: its id (API key) and French display name (Story 13.8a). */
public record ModuleRefDto(String moduleId, String displayName) {

    public static ModuleRefDto from(ModuleLifecycle.ModuleRef ref) {
        return new ModuleRefDto(ref.moduleId(), ref.displayName());
    }
}
