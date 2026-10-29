package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link EntitlementService} of the core (Story 13.5): a thin layer over {@link ModuleRegistry#moduleState} /
 * {@link ModuleRegistry#moduleStates}, so for every tenant schema the registry and the entitlement check give the
 * same state (one computation, {@code ModuleStateRules}).
 *
 * <p>One deliberate exception, the platform-admin schema ({@code "public"}): the registry answers {@code core} +
 * the installed {@code platform} modules (what a super-admin session shows), while this service answers every
 * installed module ENABLED (spec 13.5: the super-admin is not gated by subscriptions). An uninstalled id stays
 * DISABLED for both.
 */
@Service
public class ModuleEntitlementService implements EntitlementService {

    private final ModuleRegistry registry;

    public ModuleEntitlementService(ModuleRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ModuleState state(String tenantSchema, String moduleId) {
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return moduleId != null && registry.definition(moduleId).isPresent()
                    ? ModuleState.ENABLED : ModuleState.DISABLED;
        }
        return registry.moduleState(tenantSchema, moduleId);
    }

    @Override
    public boolean isEnabled(String tenantSchema, String moduleId) {
        return state(tenantSchema, moduleId).readable();
    }

    @Override
    public boolean isReadOnly(String tenantSchema, String moduleId) {
        return state(tenantSchema, moduleId) == ModuleState.READ_ONLY;
    }

    @Override
    public void assertEnabled(String tenantSchema, String moduleId) {
        if (!isEnabled(tenantSchema, moduleId)) {
            throw notEntitled();
        }
    }

    @Override
    public void assertWritable(String tenantSchema, String moduleId) {
        switch (state(tenantSchema, moduleId)) {
            case ENABLED -> {
            }
            case READ_ONLY -> throw new DomainException(ErrorCode.MODULE_READ_ONLY,
                    "Module is read-only for the tenant (subscription expired, grace period)");
            case DISABLED -> throw notEntitled();
        }
    }

    @Override
    public Set<String> enabledModules(String tenantSchema) {
        if (TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(tenantSchema)) {
            return sortedImmutable(registry.installed().stream().map(ModuleManifest::id).toList());
        }
        return sortedImmutable(registry.moduleStates(tenantSchema).entrySet().stream()
                .filter(e -> e.getValue().readable()).map(Map.Entry::getKey).toList());
    }

    /** The module id is not echoed (caller-controlled input, Story 12.12 D5). */
    private static DomainException notEntitled() {
        return new DomainException(ErrorCode.MODULE_NOT_ENTITLED, "Module is not included in the tenant's subscription");
    }

    private static Set<String> sortedImmutable(java.util.Collection<String> ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(ids)));
    }
}
