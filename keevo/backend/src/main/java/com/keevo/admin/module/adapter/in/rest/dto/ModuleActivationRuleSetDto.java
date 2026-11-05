package com.keevo.admin.module.adapter.in.rest.dto;

import com.keevo.shared.module.ModuleActivationRulesAdmin;

import java.time.Instant;
import java.util.List;

/**
 * Story 19.13 — one initial module activation rule set for the super-admin console: sale flow ({@code POS} « Caisse »
 * | {@code DOCUMENT} « Documents »), sector ({@code null}: default set of the flow, not deletable), the modules
 * disabled at start for a new tenant (with their French names), last change.
 */
public record ModuleActivationRuleSetDto(String saleFlow, String saleFlowLabel, String sectorType,
                                         List<ModuleRefDto> disabledModules, boolean defaultSet, Instant updatedAt) {

    public static ModuleActivationRuleSetDto from(ModuleActivationRulesAdmin.RuleSetView view) {
        return new ModuleActivationRuleSetDto(view.saleFlow(),
                com.keevo.identity.api.SaleFlow.fromCode(view.saleFlow())
                        .map(com.keevo.identity.api.SaleFlow::label).orElse(null),
                view.sectorType(), view.disabledModules().stream().map(ModuleRefDto::from).toList(),
                view.defaultSet(), view.updatedAt());
    }
}
