package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.BundleAdmin;

import java.util.List;

/**
 * Response of a bundle application (Story 16.2): the bundle's name, the modules written by the call (dependencies
 * first; empty when nothing changed) and the bundle's modules that were already active.
 */
public record BundleActivationDto(String name, List<ModuleRefDto> activated, List<ModuleRefDto> alreadyActive) {

    public static BundleActivationDto from(BundleAdmin.ApplyResult result) {
        return new BundleActivationDto(result.name(), result.activated().stream().map(ModuleRefDto::from).toList(),
                result.alreadyActive().stream().map(ModuleRefDto::from).toList());
    }
}
