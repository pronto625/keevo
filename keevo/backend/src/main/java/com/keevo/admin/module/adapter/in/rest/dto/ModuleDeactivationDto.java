package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleLifecycle;

/** Response of a deactivation (Story 13.8a): the module and its stored status ({@code DISABLED}). */
public record ModuleDeactivationDto(String moduleId, String displayName, String status) {

    public static ModuleDeactivationDto from(ModuleLifecycle.DeactivationResult result) {
        return new ModuleDeactivationDto(result.moduleId(), result.displayName(), result.status().name());
    }
}
