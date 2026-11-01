package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.BundleAdmin;

import java.util.List;

/**
 * A bundle (Story 16.2): id, French name, optional description, modules with their display names in bundle order,
 * and whether it can still be applied (a module no longer available shows as « Module indisponible »).
 */
public record BundleDto(String id, String name, String description, List<ModuleRefDto> modules,
                        boolean applicable) {

    public static BundleDto from(BundleAdmin.BundleView view) {
        return new BundleDto(view.id(), view.name(), view.description(),
                view.modules().stream().map(ModuleRefDto::from).toList(), view.applicable());
    }
}
