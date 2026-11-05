package com.keevo.shared.module.application;

import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.OwnerModuleActivationStore;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Row;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.keevo.shared.module.TestModuleCatalogs.loaded;

/**
 * Story 19.13 — fakes of the owner layer: a catalogue shaped like the shipped one (pos / counting / multi-store require
 * inventory, exports requires reporting; store, sync, messaging not owner-toggleable; invoicing / exports paid), one tenant whose plan rows and
 * owner choices live in memory, read by both the registry port and the owner-activation store.
 */
final class OwnerModuleActivationTestSupport {

    static final String SCHEMA = "kv_own013";
    static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000013");

    /** Paid modules in the plan (an ACTIVE row); a paid module without a row is outside the plan. */
    final Map<String, Row> planRows = new HashMap<>();
    /** module id → activated (no entry = activated). */
    final Map<String, Boolean> ownerRows = new LinkedHashMap<>();
    final List<String> upserts = new ArrayList<>();

    static ModuleCatalog catalog() {
        ModuleCategory b = ModuleCategory.BUSINESS;
        ModuleCategory p = ModuleCategory.PREMIUM;
        return new ModuleCatalog(List.of(
                loaded("core", ModuleCategory.CORE, true),
                notToggleable("sync", ModuleCategory.CORE, List.of()),
                notToggleable("store", b, List.of()),
                notToggleable("messaging", b, List.of()),
                loaded("admin", ModuleCategory.PLATFORM, true),
                loaded("catalog", b, true),
                loaded("inventory", b, true, List.of("catalog"), List.of()),
                named("pos", "Point de vente", b, true, List.of("catalog", "inventory")),
                named("counting", "Inventaire assisté", p, false, List.of("inventory")),
                named("multi-store", "Multi-boutiques et transferts", p, false, List.of("store", "inventory")),
                named("invoicing", "Facturation", p, false, List.of()),
                named("reporting", "Rapports", b, true, List.of()),
                named("exports", "Exports PDF et Excel", p, false, List.of("reporting"))));
    }

    private static LoadedModuleManifest named(String id, String name, ModuleCategory category, boolean free,
                                              List<String> requires) {
        List<String> all = new ArrayList<>(List.of("core"));
        all.addAll(requires);
        return new LoadedModuleManifest("test:" + id, new ModuleManifest(id, "1.0.0", name, category, all, List.of(),
                List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(), "module." + id, free), "{}");
    }

    private static LoadedModuleManifest notToggleable(String id, ModuleCategory category, List<String> requires) {
        List<String> all = new ArrayList<>(List.of("core"));
        all.addAll(requires);
        return new LoadedModuleManifest("test:" + id, new ModuleManifest(id, "1.0.0", id, category, all, List.of(),
                List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(), "module." + id, true,
                ModuleManifest.noRolePermissions(), false), "{}");
    }

    void inPlan(String paidModule) {
        planRows.put(paidModule, Row.of(TenantModuleStatus.ACTIVE));
    }

    void readOnly(String paidModule, Instant now) {
        planRows.put(paidModule, new Row(TenantModuleStatus.EXPIRED, now.minusSeconds(3600), now.plusSeconds(3600),
                "STANDARD"));
    }

    TenantModuleStatePort statePort() {
        return new TenantModuleStatePort() {
            @Override
            public Optional<Map<String, Row>> findRows(String tenantSchema) {
                return findTenantRows(tenantSchema).map(TenantRows::rows);
            }

            @Override
            public Optional<TenantRows> findTenantRows(String tenantSchema) {
                if (!SCHEMA.equals(tenantSchema)) {
                    return Optional.empty();
                }
                return Optional.of(new TenantRows(planRows, Plan.NONE, ownerRows.entrySet().stream()
                        .filter(e -> !e.getValue()).map(Map.Entry::getKey).collect(Collectors.toSet())));
            }
        };
    }

    OwnerModuleActivationStore ownerStore() {
        return new OwnerModuleActivationStore() {
            @Override
            public Optional<UUID> findTenantId(String tenantSchema) {
                return SCHEMA.equals(tenantSchema) ? Optional.of(TENANT_ID) : Optional.empty();
            }

            @Override
            public Map<String, Boolean> findActivations(UUID tenantId) {
                return Map.copyOf(ownerRows);
            }

            @Override
            public void upsert(UUID tenantId, String moduleId, boolean activated, UUID actorId, Instant at) {
                ownerRows.put(moduleId, activated);
                upserts.add(moduleId + "=" + activated);
            }
        };
    }
}
