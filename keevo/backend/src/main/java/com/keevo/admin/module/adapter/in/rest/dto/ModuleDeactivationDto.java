package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleLifecycle;

import java.util.List;

/**
 * Response of a deactivation (Story 13.8a): the module and its stored status ({@code DISABLED}); Story 16.4: every
 * module the call deactivated (dependents first with {@code cascade}) and those deactivated although their
 * deactivation hook failed ({@code force}).
 */
public record ModuleDeactivationDto(String moduleId, String displayName, String status,
                                    List<ModuleRefDto> deactivated, List<ModuleRefDto> forced) {

    public static ModuleDeactivationDto from(ModuleLifecycle.DeactivationResult result) {
        return new ModuleDeactivationDto(result.moduleId(), result.displayName(), result.status().name(),
                result.deactivated().stream().map(ModuleRefDto::from).toList(),
                result.forced().stream().map(ModuleRefDto::from).toList());
    }
}
