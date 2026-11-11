package com.keevo.shared.module.application;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowQueryPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.onboarding.SectorKind;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleActivationRulesAdmin;
import com.keevo.shared.module.ModuleActivationRulesPort;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.application.port.ModuleActivationRuleStore;
import com.keevo.shared.module.application.port.ModuleActivationRuleStore.StoredRuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 19.13 — the initial module activation rules (ADR-05, Strategy-by-data: the most specific rule set wins):
 * {@link ModuleActivationRulesPort} (apply to a new tenant) and {@link ModuleActivationRulesAdmin} (super-admin
 * editing, logged {@code [ADMIN AUDIT]} like the bundles). Rule sets are validated against the installed manifests;
 * nothing is hard-coded per plan or per sector, and existing tenants are never touched by an edit.
 *
 * <p>Complexity: apply = one read of the tenant's preferences + one {@code INSERT … SELECT}; admin = O(rule sets).
 */
@Service
public class ModuleActivationRulesService implements ModuleActivationRulesPort, ModuleActivationRulesAdmin {

    private static final Logger log = LoggerFactory.getLogger(ModuleActivationRulesService.class);

    private final ModuleCatalog catalog;
    private final ModuleActivationRuleStore store;
    private final TenantSaleFlowQueryPort saleFlows;
    private final ModuleRegistry registry;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ModuleActivationRulesService(ModuleCatalog catalog, ModuleActivationRuleStore store,
                                        TenantSaleFlowQueryPort saleFlows, ModuleRegistry registry,
                                        PlatformTransactionManager transactionManager, Clock clock) {
        this.catalog = catalog;
        this.store = store;
        this.saleFlows = saleFlows;
        this.registry = registry;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ── apply ────────────────────────────────────────────────────────────────

    @Override
    public int applyInitialRules(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        TenantSaleFlowQueryPort.TenantSaleFlowView profile = saleFlows.find(schema)
                .orElse(new TenantSaleFlowQueryPort.TenantSaleFlowView(SaleFlow.POS, null));
        int disabled = store.applyInitial(schema, profile.saleFlow().name(), profile.sectorType(), clock.instant());
        registry.evict(schema);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registry.evict(schema);
                }
            });
        }
        log.info("Module activation rules applied: flow={} sector={} modulesDisabled={}", profile.saleFlow(),
                profile.sectorType(), disabled);
        return disabled;
    }

    @Override
    public int ownerDisableAllBut(String tenantSchema, Set<String> keep) {
        String schema = TenantSchema.validate(tenantSchema);
        List<String> disabled = modulesToDisable(keep == null ? Set.of() : keep);
        int inserted = store.disable(schema, disabled, clock.instant());
        registry.evict(schema);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registry.evict(schema);
                }
            });
        }
        log.info("Modules owner-disabled outside the kept set: disabled={} inserted={}", disabled, inserted);
        return inserted;
    }

    /**
     * The installed, owner-toggleable, non-platform modules outside {@code keep}, minus every module that a module left
     * enabled (kept, or not owner-toggleable) requires — transitively, so no enabled module loses a requirement.
     * Sorted by id. One worklist pass from the modules left enabled: O(m + r) over the m installed manifests and
     * their r requirement edges, no I/O.
     */
    List<String> modulesToDisable(Set<String> keep) {
        Map<String, ModuleManifest> modules = new java.util.HashMap<>();
        catalog.manifests().stream().filter(m -> m.category() != ModuleCategory.PLATFORM)
                .forEach(m -> modules.put(m.id(), m));
        Set<String> disable = new java.util.TreeSet<>();
        java.util.ArrayDeque<ModuleManifest> enabled = new java.util.ArrayDeque<>();
        for (ModuleManifest m : modules.values()) {
            if (m.ownerCanToggle() && !keep.contains(m.id())) {
                disable.add(m.id());
            } else {
                enabled.add(m);
            }
        }
        while (!enabled.isEmpty()) {
            for (String required : enabled.poll().requires()) {
                if (disable.remove(required) && modules.containsKey(required)) {
                    enabled.add(modules.get(required)); // now enabled: its own requirements stay enabled too
                }
            }
        }
        return List.copyOf(disable);
    }

    // ── admin ────────────────────────────────────────────────────────────────

    @Override
    public List<RuleSetView> list() {
        return store.findAll().stream().map(this::view).toList();
    }

    @Override
    public SaveResult save(String saleFlow, String sectorType, List<String> disabledModules, UUID actorId) {
        SaleFlow flow = requireFlow(saleFlow);
        String sector = sector(sectorType);
        List<String> modules = validModules(disabledModules);
        boolean created = Boolean.TRUE.equals(tx.execute(status ->
                store.save(flow.name(), sector, modules, clock.instant())));
        log.info("[ADMIN AUDIT] Module activation rules {}: actor={} flow={} sector={} disabledModules={}",
                created ? "created" : "replaced", actorId, flow, sector, modules);
        RuleSetView view = store.findAll().stream()
                .filter(s -> s.saleFlow().equals(flow.name()) && java.util.Objects.equals(s.sectorType(), sector))
                .findFirst().map(this::view)
                .orElseGet(() -> new RuleSetView(flow.name(), sector, refs(modules), sector == null, clock.instant()));
        return new SaveResult(view, created);
    }

    @Override
    public void delete(String saleFlow, String sectorType, UUID actorId) {
        SaleFlow flow = requireFlow(saleFlow);
        String sector = sector(sectorType);
        if (sector == null) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE,
                    "Les règles par défaut d'un parcours de vente ne peuvent pas être supprimées");
        }
        if (!Boolean.TRUE.equals(tx.execute(status -> store.delete(flow.name(), sector)))) {
            throw DomainException.userFacing(ErrorCode.NOT_FOUND, "Règle introuvable");
        }
        log.info("[ADMIN AUDIT] Module activation rules deleted: actor={} flow={} sector={} (tenants unchanged)",
                actorId, flow, sector);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SaleFlow requireFlow(String saleFlow) {
        return SaleFlow.fromCode(saleFlow).orElseThrow(() ->
                invalid("parcours de vente inconnu : choisissez Caisse ou Documents"));
    }

    /**
     * A known sector code, or {@code null} for the flow's default set (JSON null / absent parameter only: a blank
     * string is refused, it must never silently target the default set).
     */
    private static String sector(String sectorType) {
        if (sectorType == null) {
            return null;
        }
        if (sectorType.isBlank()) {
            throw DomainException.userFacing(ErrorCode.MODULE_ACTIVATION_RULE_INVALID, "Secteur inconnu",
                    Map.of("reason", "secteur inconnu"));
        }
        for (SectorKind kind : SectorKind.values()) {
            if (kind.name().equals(sectorType.strip())) {
                return kind.name();
            }
        }
        throw invalid("secteur d'activité inconnu");
    }

    /**
     * Installed, owner-toggleable, no duplicates (empty allowed: nothing disabled at start), dependency-closed: every
     * tenant module requiring a listed module is listed too. Sorted by id.
     */
    private List<String> validModules(List<String> requested) {
        if (requested == null) {
            throw invalid("indiquez les modules désactivés au départ (liste vide si aucun)");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : requested) {
            ModuleManifest module = id == null ? null : catalog.find(id.strip())
                    .filter(m -> m.category() != ModuleCategory.PLATFORM).orElse(null);
            if (module == null) {
                throw invalid("un des modules demandés n'existe pas");
            }
            if (!module.ownerCanToggle()) {
                throw invalid("« " + module.displayName() + " » ne peut pas être désactivé");
            }
            if (!ids.add(module.id())) {
                throw invalid("« " + module.displayName() + " » figure plusieurs fois dans la liste");
            }
        }
        for (String id : ids) {
            List<String> missing = catalog.manifests().stream()
                    .filter(m -> m.category() != ModuleCategory.PLATFORM && !ids.contains(m.id())
                            && m.requires().contains(id))
                    .sorted(Comparator.comparing(ModuleManifest::id))
                    .map(ModuleManifest::displayName).toList();
            if (!missing.isEmpty()) {
                throw invalid("« " + catalog.find(id).orElseThrow().displayName() + " » est requis par "
                        + String.join(", ", missing) + " : désactivez-les aussi");
            }
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.naturalOrder());
        return List.copyOf(sorted);
    }

    private RuleSetView view(StoredRuleSet set) {
        return new RuleSetView(set.saleFlow(), set.sectorType(), refs(set.modules()), set.sectorType() == null,
                set.updatedAt());
    }

    private List<ModuleLifecycle.ModuleRef> refs(List<String> modules) {
        return modules.stream()
                .map(id -> new ModuleLifecycle.ModuleRef(id, catalog.find(id).map(ModuleManifest::displayName)
                        .orElse("Module indisponible")))
                .toList();
    }

    private static DomainException invalid(String reason) {
        return DomainException.userFacing(ErrorCode.MODULE_ACTIVATION_RULE_INVALID, "Règle non enregistrée : "
                + reason, Map.of("reason", reason));
    }
}
