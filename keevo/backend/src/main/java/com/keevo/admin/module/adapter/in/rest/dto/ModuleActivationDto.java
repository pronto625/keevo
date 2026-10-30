package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleLifecycle;

import java.time.Instant;
import java.util.List;

/**
 * Response of an activation (Story 13.8a): the modules written by the call (dependencies first; empty when nothing
 * changed), the target's status / end of subscription / provisioned version.
 */
public record ModuleActivationDto(List<ModuleRefDto> activated, String status, Instant expiresAt,
                                  String provisionedVersion) {

    public static ModuleActivationDto from(ModuleLifecycle.ActivationResult result) {
        return new ModuleActivationDto(result.activated().stream().map(ModuleRefDto::from).toList(),
                result.status().name(), result.expiresAt(), result.provisionedVersion());
    }
}
