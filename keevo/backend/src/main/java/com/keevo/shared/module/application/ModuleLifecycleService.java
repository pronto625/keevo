package com.keevo.shared.module.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.ActivationPlan;
import com.keevo.shared.module.ActorRef;
import com.keevo.shared.module.BlockReason;
import com.keevo.shared.module.DeactivationCheck;
import com.keevo.shared.module.DependencyResolver;
import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.ModuleActivationEligibility;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ModuleTenantContext;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.TenantModuleLifecyclePort;
import com.keevo.shared.module.application.port.TenantModuleLifecyclePort.StoredRow;
import com.keevo.shared.module.application.port.TenantModuleLifecyclePort.TenantRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link ModuleLifecycle} of the core (Story 13.8a, spec-socle §10). Each call: take the per-tenant transaction lock
 * {@code module-lifecycle:<tenant UUID>} (first statement of the transaction), resolve the tenant (UUID → live
 * schema), plan with the {@link DependencyResolver} <b>before</b> any write, write the
 * {@code tenant_modules} rows in plan order — each row first, then {@link ModuleProvisioner#provision} — evict the
 * registry request cache after the writes and again after completion, and audit after commit.
 *
 * <p><b>Granting paid modules.</b> The {@link ModuleActivationEligibility} bean only allows free modules (Story
 * 13.5). For a super-admin actor the plan is computed with a lifecycle-scoped resolver whose eligibility treats every
 * step as eligible (V1: the super-admin activation after an off-platform payment <i>is</i> the entitlement). That
 * resolver is not a bean: every other caller keeps the free-only rule.
 *
 * <p><b>Rows.</b> A free module (target or dependency) is written {@code ACTIVE} without end date; a paid one gets
 * the requested status and end date, which is then mandatory for {@code ACTIVE} (a {@code TRIAL} without one is the
 * 14-day reactivation trial of a paid target that is not enabled, and no change on an enabled one, Story 16.1; at
 * most 10 years). Story 16.1: dependencies that are not ENABLED (read-only
 * or disabled) are part of the plan, so they are renewed with the target's status and end date;
 * {@code grace_until = expires_at + 7 days}; {@code activated_at = now} only on an inactive → active transition
 * (kept otherwise), {@code disabled_at = null}. Deactivation upserts {@code DISABLED} + {@code disabled_at}
 * and never deletes a row (a free module without a row is active). A provisioning failure rolls the whole
 * transaction back (rows as before — but tables created by migrations that already ran stay, Flyway commits on its
 * own connection) and answers {@code MODULE_PROVISIONING_FAILED} without technical detail.
 *
 * <p><b>Several modules (Story 16.2, bundles).</b> {@link #activateModules} plans the union of the targets'
 * closures once ({@link DependencyResolver#planActivation(String, java.util.Set)}) and runs the same steps; {@link
 * #activate} is its one-element case, with one difference: a single activation with new terms on an ENABLED paid
 * target rewrites it (deliberate renewal), while a set activation never rewrites an ENABLED target (listed as already
 * active) — only the non-enabled targets and the dependencies they need are written.
 *
 * <p><b>TRIAL without an end date (Stories 16.1/16.2).</b> It always means 14 days for every paid module the call
 * writes — the target and its paid dependencies alike, including the paid dependency of a free target (formerly a
 * 422) and the paid repair dependencies of an ENABLED paid target (formerly given the target's stored terms). An
 * enabled paid target keeps its stored status and end and is not written.
 *
 * <p><b>Idempotence.</b> An enabled target with an empty plan and the same status/expiry writes no row and is not
 * audited, but is still provisioned (no-op when up to date): a free module without a row is active by the free rule
 * (Story 13.2) yet may never have had its tables created.
 *
 * <p><b>Module hooks (Story 13.9).</b> For a module implemented by a {@link KeevoModule} bean, the call invokes
 * {@link KeevoModule#onActivate} for each step that goes from inactive (DISABLED) to active, right after its
 * provisioning — never for a renewal, a status / expiry change of an already active module, nor a free module
 * already active by the free rule — and {@link KeevoModule#onDeactivate} right after the {@code DISABLED} write,
 * inside the same transaction and lock. A hook exception rolls the whole call back ({@code MODULE_ACTIVATION_FAILED}
 * / {@code MODULE_DEACTIVATION_FAILED}, French message without technical detail): the {@code tenant_modules} rows,
 * but <b>not</b> DDL already committed by the module's migrations (Flyway runs on its own connection). An idempotent
 * call (already enabled / already disabled) writes nothing and calls no hook.
 *
 * <p><b>Scope.</b> {@code platform} modules are not tenant modules ({@code MODULE_NOT_FOUND}); modules of category
 * {@code core} ({@code core}, {@code sync}) are never deactivated ({@code CORE_MODULE_REQUIRED}).
 */
@Service
public class ModuleLifecycleService implements ModuleLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ModuleLifecycleService.class);

    static final String LOCK_PREFIX = "module-lifecycle:";
    /** Read-only grace after the end of a subscription (spec-socle §2.2). */
    static final Duration GRACE = Duration.ofDays(7);
    /** Story 16.1 (MOD-FR13) — length of a trial granted without an end date (reactivation trial). */
    static final Duration REACTIVATION_TRIAL = Duration.ofDays(14);
    /** Farthest end date accepted (a typo must not grant decades, nor overflow the column). */
    static final int MAX_EXPIRY_YEARS = 10;
    static final String ACTION_ACTIVATED = "MODULE_ACTIVATED";
    static final String ACTION_DEACTIVATED = "MODULE_DEACTIVATED";
    /**
     * Story 16.4 — kinds told apart in the audit metadata ({@code kind}), on the existing action codes (the owner's
     * history and exports keep their labels): a deactivation written although the module's deactivation hook failed
     * ({@code MODULE_DEACTIVATED}), a pack applied by the super-admin ({@code MODULE_ACTIVATED}, one entry per
     * application that wrote something).
     */
    static final String KIND_FORCED = "FORCED";
    static final String KIND_BUNDLE = "BUNDLE";
    /** Story 16.4 — how many history entries the console shows. */
    static final int HISTORY_SIZE = 100;
    static final String ENTITY_TYPE = "Module";
    static final String ACTOR_LABEL = "Administrateur Keevo";
    static final String TIER_FREE = "FREE";
    static final String TIER_PAID = "STANDARD";
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ModuleCatalog catalog;
    private final ModuleRegistry registry;
    private final DependencyResolver resolver;
    private final DependencyResolver grantingResolver;
    private final ModuleProvisioner provisioner;
    private final TenantModuleLifecyclePort store;
    private final TransactionLockPort locks;
    private final AuditPort audit;
    private final Clock clock;
    private final KeevoModuleDirectory modules;
    private final TransactionOperations hookTransaction;

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** Without module beans (no hook called) — tests and catalogues without converted modules. */
    public ModuleLifecycleService(ModuleCatalog catalog, ModuleRegistry registry, DependencyResolver resolver,
                                  ModuleProvisioner provisioner, TenantModuleLifecyclePort store,
                                  TransactionLockPort locks, AuditPort audit, Clock clock) {
        this(catalog, registry, resolver, provisioner, store, locks, audit, clock,
                KeevoModuleDirectory.empty(catalog));
    }

    /** Forced-deactivation hooks run without their own transaction — tests without a transaction manager. */
    public ModuleLifecycleService(ModuleCatalog catalog, ModuleRegistry registry, DependencyResolver resolver,
                                  ModuleProvisioner provisioner, TenantModuleLifecyclePort store,
                                  TransactionLockPort locks, AuditPort audit, Clock clock,
                                  KeevoModuleDirectory modules) {
        this(catalog, registry, resolver, provisioner, store, locks, audit, clock, modules,
                TransactionOperations.withoutTransaction());
    }

    @Autowired
    public ModuleLifecycleService(ModuleCatalog catalog, ModuleRegistry registry, DependencyResolver resolver,
                                  ModuleProvisioner provisioner, TenantModuleLifecyclePort store,
                                  TransactionLockPort locks, AuditPort audit, Clock clock,
                                  KeevoModuleDirectory modules, PlatformTransactionManager transactionManager) {
        this(catalog, registry, resolver, provisioner, store, locks, audit, clock, modules,
                requiresNew(transactionManager));
    }

    /**
     * @param hookTransaction how a forced deactivation isolates the module's {@link KeevoModule#onDeactivate} hook
     *                        (production: its own {@code REQUIRES_NEW} transaction, so a failing hook — even one
     *                        marking its transaction rollback-only — never touches the deactivation's transaction)
     */
    public ModuleLifecycleService(ModuleCatalog catalog, ModuleRegistry registry, DependencyResolver resolver,
                                  ModuleProvisioner provisioner, TenantModuleLifecyclePort store,
                                  TransactionLockPort locks, AuditPort audit, Clock clock,
                                  KeevoModuleDirectory modules, TransactionOperations hookTransaction) {
        this.hookTransaction = hookTransaction;
        this.modules = modules;
        this.catalog = catalog;
        this.registry = registry;
        this.resolver = resolver;
        this.grantingResolver = new DependencyResolverService(catalog, registry, new GrantingEligibility());
        this.provisioner = provisioner;
        this.store = store;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
    }

    /** Lifecycle-scoped eligibility of a super-admin activation: every module is eligible, initial status ACTIVE. */
    static final class GrantingEligibility implements ModuleActivationEligibility {

        @Override
        public Eligibility check(String tenantSchema, ModuleManifest module) {
            return Eligibility.eligible(TenantModuleStatus.ACTIVE);
        }
    }

    // ── activation ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ActivationResult activate(UUID tenantId, String moduleId, ActivationRequest request, Actor actor) {
        Objects.requireNonNull(actor, "actor");
        Instant now = clock.instant();
        lockTenant(tenantId); // first statement of the transaction
        TenantRef tenant = requireTenant(tenantId);
        ModuleManifest target = requireTenantModule(moduleId);
        // Story 16.2: a single activation is the one-element case of the set activation
        Outcome outcome = activateAll(tenant, List.of(target), request, actor, now, true);
        if (target.isCore()) { // always enabled, never a row
            return new ActivationResult(List.of(), TenantModuleStatus.ACTIVE, null, null);
        }
        return result(outcome.activated(), outcome.rows().get(target.id()));
    }

    @Override
    @Transactional
    public ModulesActivationResult activateModules(UUID tenantId, java.util.Collection<String> moduleIds,
                                                   ActivationRequest request, Actor actor) {
        return activateSet(tenantId, null, moduleIds, request, actor);
    }

    @Override
    @Transactional
    public ModulesActivationResult applyBundle(UUID tenantId, String bundleName,
                                               java.util.Collection<String> moduleIds, ActivationRequest request,
                                               Actor actor) {
        return activateSet(tenantId, Objects.requireNonNull(bundleName, "bundleName"), moduleIds, request, actor);
    }

    /** The set activation; with a pack name, one « Pack appliqué » audit entry after commit when something changed. */
    private ModulesActivationResult activateSet(UUID tenantId, String bundleName,
                                                java.util.Collection<String> moduleIds, ActivationRequest request,
                                                Actor actor) {
        Objects.requireNonNull(actor, "actor");
        Instant now = clock.instant();
        lockTenant(tenantId); // first statement of the transaction
        TenantRef tenant = requireTenant(tenantId);
        if (moduleIds == null || moduleIds.isEmpty()) {
            throw invalid("modules", "Choisissez au moins un module à activer");
        }
        Map<String, ModuleManifest> requested = new LinkedHashMap<>();
        for (String moduleId : moduleIds) {
            ModuleManifest module = requireTenantModule(moduleId);
            requested.putIfAbsent(module.id(), module);
        }
        Outcome outcome = activateAll(tenant, List.copyOf(requested.values()), request, actor, now, false);
        if (bundleName != null && !outcome.activated().isEmpty()) {
            List<String> names = outcome.activated().stream().map(ModuleRef::displayName).toList();
            afterCommit(() -> recordBundleApplied(tenant, actor, bundleName, names, request));
        }
        return new ModulesActivationResult(outcome.activated(), outcome.alreadyActive());
    }

    /**
     * The activation of {@code requested} (Story 13.8a, set form since Story 16.2), under the tenant lock already
     * taken: validation, one plan over the union of the closures, rows + provisioning + hooks in plan order, audit
     * after commit. Writes nothing when a validation or the plan refuses the call.
     *
     * @param renewEnabled whether an ENABLED paid target is rewritten when the request brings other terms (single
     *                     activation: a deliberate renewal); {@code false} for a set (bundle): ENABLED targets are
     *                     never rewritten and are listed as already active
     */
    private Outcome activateAll(TenantRef tenant, List<ModuleManifest> requested, ActivationRequest request,
                                Actor actor, Instant now, boolean renewEnabled) {
        List<ModuleManifest> targets = requested.stream().filter(m -> !m.isCore()).toList();
        if (targets.isEmpty()) { // core: always enabled, never a row — nothing to validate
            return new Outcome(List.of(), refs(requested), Map.of());
        }
        TenantModuleStatus status = requestedStatus(request);
        Instant expiresAt = requestedExpiry(request, now);
        String schema = tenant.tenantSchema();
        registry.evict(schema); // never plan from rows read before the lock
        registerEviction(schema);

        Map<String, StoredRow> rows = store.findStoredRows(schema);
        // Story 16.1 (MOD-FR13): a trial without an end date on a paid module that is not enabled is the 14-day
        // reactivation trial; on an enabled target it changes nothing (never cuts a running subscription short, never
        // extends a running trial on each call): the stored status and end are kept
        boolean defaultTrial = status == TenantModuleStatus.TRIAL && expiresAt == null;
        Map<String, Terms> kept = new java.util.HashMap<>();
        if (defaultTrial) {
            for (ModuleManifest target : targets) {
                if (!target.free() && registry.moduleState(schema, target.id()) == ModuleState.ENABLED) {
                    StoredRow current = rows.get(target.id());
                    kept.put(target.id(), current != null ? new Terms(current.status(), current.expiresAt())
                            : new Terms(TenantModuleStatus.ACTIVE, null));
                }
            }
        }
        Terms requestedTerms = new Terms(status, defaultTrial ? reactivationTrialEnd(now) : expiresAt);
        // a free module never gets a status other than ACTIVE nor an expiry; a paid one (target or dependency) gets
        // the requested status and end date (or keeps its own, see above)
        java.util.function.Function<ModuleManifest, Terms> termsOf = module -> module.free()
                ? new Terms(TenantModuleStatus.ACTIVE, null)
                : kept.getOrDefault(module.id(), requestedTerms);

        java.util.Set<String> targetIds = new java.util.TreeSet<>();
        targets.forEach(t -> targetIds.add(t.id()));
        ActivationPlan plan = (actor.superAdmin() ? grantingResolver : resolver).planActivation(schema, targetIds);
        if (plan.blocked()) {
            throw blocked(plan);
        }
        List<String> planned = new ArrayList<>(plan.moduleIds());
        for (ModuleManifest target : targets) {
            if (!planned.contains(target.id())
                    && (registry.moduleState(schema, target.id()) != ModuleState.ENABLED
                        || (renewEnabled && !target.free()
                            && changes(rows.get(target.id()), termsOf.apply(target))))) {
                planned.add(target.id()); // renewal of a read-only module, or a new status/expiry of a paid one
            }
        }
        List<String> toWrite = dependencyOrder(planned);
        checkExpiryRules(targets, toWrite, termsOf, status, expiresAt);
        if (toWrite.isEmpty()) {
            // already enabled: make sure their tables exist — and those of their dependencies active by the free rule
            // (no row): such modules are active but may never have been provisioned. No-op when up to date.
            java.util.Set<String> toProvision = new java.util.LinkedHashSet<>();
            for (String id : plan.alreadyActive()) {
                if (targetIds.contains(id) || !rows.containsKey(id)) {
                    toProvision.add(id);
                }
            }
            toProvision.addAll(targetIds);
            for (String id : dependencyOrder(List.copyOf(toProvision))) {
                provision(tenant, catalog.find(id).orElseThrow());
            }
            registry.evict(schema);
            return new Outcome(List.of(), refs(requested), store.findStoredRows(schema));
        }

        List<Written> written = new ArrayList<>();
        for (String id : toWrite) {
            ModuleManifest module = catalog.find(id).orElseThrow();
            Terms terms = termsOf.apply(module);
            Instant grace = terms.expiresAt() != null ? terms.expiresAt().plus(GRACE) : null;
            // activated_at is only set on an inactive → active transition
            boolean wasActive = registry.moduleState(schema, id) != ModuleState.DISABLED;
            UUID rowId = store.upsertActivated(tenant.id(), id, terms.status(),
                    module.free() ? TIER_FREE : TIER_PAID, now, wasActive, terms.expiresAt(), grace);
            registry.evict(schema);
            provision(tenant, module);
            if (!wasActive) { // only an inactive → active transition (not a renewal / status change)
                onActivate(tenant, actor, module);
            }
            written.add(new Written(rowId, module, terms.status(), terms.expiresAt()));
        }
        // repair plan (dependencies only): the tables of the targets not written too
        for (String id : dependencyOrder(targetIds.stream().filter(t -> !toWrite.contains(t)).toList())) {
            provision(tenant, catalog.find(id).orElseThrow());
        }
        registry.evict(schema);

        List<ModuleRef> activated = written.stream()
                .map(w -> new ModuleRef(w.module().id(), w.module().displayName())).toList();
        afterCommit(() -> {
            log.info("[ADMIN AUDIT] Modules activated: actor={} tenant={} modules={}", actor.userId(), tenant.code(),
                    written.stream().map(w -> w.module().id()).toList());
            written.forEach(w -> recordActivated(tenant, actor, w));
        });
        List<ModuleRef> alreadyActive = refs(requested.stream().filter(m -> !toWrite.contains(m.id())).toList());
        return new Outcome(activated, alreadyActive, store.findStoredRows(schema));
    }

    /** What an activation call did: the modules written, the requested ones left as they were, the rows after. */
    private record Outcome(List<ModuleRef> activated, List<ModuleRef> alreadyActive, Map<String, StoredRow> rows) {
    }

    /** The status and end date a module is written with. */
    private record Terms(TenantModuleStatus status, Instant expiresAt) {
    }

    private static List<ModuleRef> refs(List<ModuleManifest> modules) {
        return modules.stream().map(m -> new ModuleRef(m.id(), m.displayName())).toList();
    }

    /** The target's stored state; a target without a row (free rule) is ACTIVE without expiry nor version. */
    private static ActivationResult result(List<ModuleRef> activated, StoredRow row) {
        return row == null ? new ActivationResult(activated, TenantModuleStatus.ACTIVE, null, null)
                : new ActivationResult(activated, row.status(), row.expiresAt(), row.provisionedVersion());
    }

    /**
     * Expiry rules (orchestrator decision, 13.8a review): every paid module the call writes needs an explicit end
     * date; a free module never gets one. {@code TRIAL} / {@code expiresAt} with only free targets are refused unless
     * the call writes a paid dependency, to which they then apply (the free targets are written ACTIVE, no end).
     */
    private void checkExpiryRules(List<ModuleManifest> targets, List<String> toWrite,
                                  java.util.function.Function<ModuleManifest, Terms> termsOf,
                                  TenantModuleStatus status, Instant expiresAt) {
        List<ModuleManifest> paid = toWrite.stream().map(id -> catalog.find(id).orElseThrow())
                .filter(m -> !m.free()).toList();
        List<ModuleManifest> withoutEnd = paid.stream().filter(m -> termsOf.apply(m).expiresAt() == null).toList();
        if (!withoutEnd.isEmpty()) {
            String names = withoutEnd.stream().map(m -> "« " + m.displayName() + " »")
                    .collect(Collectors.joining(", "));
            throw invalid("expiresAt", "Une date de fin est requise pour un module payant (" + names + ")");
        }
        boolean allFree = targets.stream().allMatch(ModuleManifest::free);
        if (allFree && paid.isEmpty() && (expiresAt != null || status != TenantModuleStatus.ACTIVE)) {
            String message = targets.size() == 1
                    ? "Le module « " + targets.get(0).displayName() + " » est gratuit : il n'a ni date de fin ni "
                      + "période d'essai"
                    : "Les modules " + targets.stream().map(m -> "« " + m.displayName() + " »")
                      .collect(Collectors.joining(", ")) + " sont gratuits : ils n'ont ni date de fin ni période "
                      + "d'essai";
            throw invalid(expiresAt != null ? "expiresAt" : "status", message);
        }
    }

    /** {@code ids} with every module after its {@code requires} (depth-first over the manifests). */
    private List<String> dependencyOrder(List<String> ids) {
        List<String> order = new ArrayList<>();
        java.util.Set<String> wanted = new java.util.HashSet<>(ids);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            visit(id, wanted, seen, order);
        }
        return order;
    }

    private void visit(String id, java.util.Set<String> wanted, java.util.Set<String> seen, List<String> order) {
        if (!seen.add(id)) {
            return;
        }
        for (String dependency : catalog.find(id).map(ModuleManifest::requires).orElse(List.of())) {
            visit(dependency, wanted, seen, order);
        }
        if (wanted.contains(id)) {
            order.add(id);
        }
    }

    private record Written(UUID rowId, ModuleManifest module, TenantModuleStatus status, Instant expiresAt) {
    }

    /** Whether the request changes the target's stored status or expiry (no row = ACTIVE without expiry). */
    private static boolean changes(StoredRow row, Terms terms) {
        if (row == null) {
            return terms.status() != TenantModuleStatus.ACTIVE || terms.expiresAt() != null;
        }
        return row.status() != terms.status() || !Objects.equals(row.expiresAt(), terms.expiresAt());
    }

    /** Runs the module's migrations; any failure becomes {@code MODULE_PROVISIONING_FAILED} (rolls back). */
    private void provision(TenantRef tenant, ModuleManifest module) {
        try {
            provisioner.provision(tenant.tenantSchema(), module.id());
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            // the provisioner's messages carry no schema value nor SQL text; the log names the tenant by code
            log.warn("Module '{}' provisioning failed for tenant {} ({}) — activation rolled back", module.id(),
                    tenant.code(), e.getClass().getSimpleName());
            // the rows are rolled back, but tables already created by earlier migrations stay (Flyway commits on
            // its own connection): never claim that nothing changed
            throw new DomainException(ErrorCode.MODULE_PROVISIONING_FAILED, "Le module « " + module.displayName()
                    + " » n'a pas été activé : la création de ses tables a échoué. Vous pouvez réessayer.");
        }
    }

    /** The module's {@link KeevoModule#onActivate} hook, if it has a bean; a failure rolls the call back. */
    private void onActivate(TenantRef tenant, Actor actor, ModuleManifest module) {
        KeevoModule bean = modules.module(module.id()).orElse(null);
        if (bean == null) {
            return;
        }
        try {
            bean.onActivate(context(tenant, actor));
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Module '{}' activation hook failed for tenant {} ({}) — activation rolled back", module.id(),
                    tenant.code(), e.getClass().getSimpleName());
            throw new DomainException(ErrorCode.MODULE_ACTIVATION_FAILED, "Le module « " + module.displayName()
                    + " » n'a pas été activé : sa mise en service a échoué. Vous pouvez réessayer.");
        }
    }

    /** The module's {@link KeevoModule#onDeactivate} hook, if it has a bean; a failure rolls the call back. */
    private void onDeactivate(TenantRef tenant, Actor actor, ModuleManifest module) {
        KeevoModule bean = modules.module(module.id()).orElse(null);
        if (bean == null) {
            return;
        }
        try {
            bean.onDeactivate(context(tenant, actor));
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Module '{}' deactivation hook failed for tenant {} ({}) — deactivation rolled back",
                    module.id(), tenant.code(), e.getClass().getSimpleName());
            throw new DomainException(ErrorCode.MODULE_DEACTIVATION_FAILED, "Le module « " + module.displayName()
                    + " » n'a pas été désactivé : sa mise hors service a échoué. Vous pouvez réessayer.");
        }
    }

    /**
     * Story 16.4 — the hook of a forced deactivation, in its own transaction ({@link #hookTransaction}): whatever it
     * does (a SQL error, a rollback-only mark) stays there. A failure is logged {@code [ADMIN AUDIT]}, never thrown.
     *
     * @return whether the hook succeeded (or the module has none)
     */
    private boolean forcedHook(TenantRef tenant, Actor actor, ModuleManifest module) {
        KeevoModule bean = modules.module(module.id()).orElse(null);
        if (bean == null) {
            return true;
        }
        try {
            hookTransaction.executeWithoutResult(status -> bean.onDeactivate(context(tenant, actor)));
            return true;
        } catch (RuntimeException e) {
            log.warn("[ADMIN AUDIT] Forced deactivation: actor={} tenant={} module={} — deactivation hook failed "
                    + "({}), module disabled anyway", actor.userId(), tenant.code(), module.id(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private static ModuleTenantContext context(TenantRef tenant, Actor actor) {
        return new ModuleTenantContext(tenant.tenantSchema(), tenant.code(),
                new ActorRef(actor.userId(), actor.superAdmin()));
    }

    private DomainException blocked(ActivationPlan plan) {
        List<String> reasons = new ArrayList<>();
        for (BlockReason reason : plan.reasons()) {
            String name = "« " + displayName(reason.moduleId()) + " »";
            reasons.add(reason.code() == BlockReason.Code.ELIGIBILITY_UNAVAILABLE
                    ? "l'accès au module " + name + " n'a pas pu être vérifié, réessayez plus tard"
                    : "le module " + name + " n'est pas inclus dans l'abonnement");
        }
        String joined = String.join(" ; ", reasons);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reasons", joined);
        details.put("modules", plan.reasons().stream().map(r -> displayName(r.moduleId())).distinct().toList());
        return new DomainException(ErrorCode.MODULE_ACTIVATION_BLOCKED, "Activation impossible : " + joined, details);
    }

    // ── deactivation ────────────────────────────────────────────────────────

    /** Overridden so the call is transactional through the proxy (an interface default method is not). */
    @Override
    @Transactional
    public DeactivationResult deactivate(UUID tenantId, String moduleId, Actor actor) {
        return deactivate(tenantId, moduleId, DeactivationOptions.NONE, actor);
    }

    @Override
    @Transactional
    public DeactivationResult deactivate(UUID tenantId, String moduleId, DeactivationOptions options, Actor actor) {
        Objects.requireNonNull(actor, "actor");
        DeactivationOptions opts = options != null ? options : DeactivationOptions.NONE;
        Instant now = clock.instant();
        lockTenant(tenantId); // first statement of the transaction
        TenantRef tenant = requireTenant(tenantId);
        ModuleManifest target = requireTenantModule(moduleId);
        String schema = tenant.tenantSchema();
        registry.evict(schema);
        registerEviction(schema);

        if (target.isCore() || target.category() == ModuleCategory.CORE) {
            throw new DomainException(ErrorCode.CORE_MODULE_REQUIRED,
                    "Le module « " + target.displayName() + " » fait partie du socle et ne peut pas être désactivé");
        }
        if (registry.moduleState(schema, target.id()) == ModuleState.DISABLED) {
            return new DeactivationResult(target.id(), target.displayName(), TenantModuleStatus.DISABLED);
        }
        List<ModuleManifest> toDisable = new ArrayList<>();
        DeactivationCheck check = resolver.checkDeactivation(schema, target.id());
        if (check.blocked()) {
            List<String> names = check.dependents().stream().map(this::displayName).toList();
            if (!opts.cascade()) {
                String joined = names.stream().map(n -> "« " + n + " »").collect(Collectors.joining(", "));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("dependents", joined);
                details.put("modules", names);
                throw new DomainException(ErrorCode.MODULE_HAS_DEPENDENTS,
                        "Désactivation impossible : ce module est utilisé par " + joined, details);
            }
            // Story 16.4 — cascade: every active dependent first, a module before the modules it requires
            List<String> order = new ArrayList<>(dependencyOrder(check.dependents()));
            java.util.Collections.reverse(order);
            for (String id : order) {
                ModuleManifest dependent = catalog.find(id).orElseThrow();
                if (dependent.isCore() || dependent.category() == ModuleCategory.CORE) {
                    throw new DomainException(ErrorCode.CORE_MODULE_REQUIRED, "Le module « "
                            + dependent.displayName() + " » fait partie du socle et ne peut pas être désactivé");
                }
                toDisable.add(dependent);
            }
        }
        toDisable.add(target);

        List<Disabled> written = new ArrayList<>();
        for (ModuleManifest module : toDisable) {
            // force: the hook runs first in its own transaction and its failure is swallowed; then the row is written
            boolean forced = opts.force() && !forcedHook(tenant, actor, module);
            UUID rowId = store.upsertDisabled(tenant.id(), module.id(), module.free() ? TIER_FREE : TIER_PAID, now);
            registry.evict(schema);
            if (!opts.force()) {
                onDeactivate(tenant, actor, module);
            }
            written.add(new Disabled(rowId, module, forced));
        }
        afterCommit(() -> {
            log.info("[ADMIN AUDIT] Module deactivated: actor={} tenant={} module={} cascade={}", actor.userId(),
                    tenant.code(), target.id(), written.stream().map(w -> w.module().id()).toList());
            written.forEach(w -> recordDeactivated(tenant, actor, w.rowId(), w.module(), w.forced()));
        });
        List<ModuleRef> deactivated = written.stream()
                .map(w -> new ModuleRef(w.module().id(), w.module().displayName())).toList();
        List<ModuleRef> forced = written.stream().filter(Disabled::forced)
                .map(w -> new ModuleRef(w.module().id(), w.module().displayName())).toList();
        return new DeactivationResult(target.id(), target.displayName(), TenantModuleStatus.DISABLED, deactivated,
                forced);
    }

    /** A module written {@code DISABLED} by a deactivation; {@code forced}: its hook failed (force). */
    private record Disabled(UUID rowId, ModuleManifest module, boolean forced) {
    }

    // ── reads ───────────────────────────────────────────────────────────────

    @Override
    public List<ModuleManifest> catalogue() {
        return catalog.manifests().stream()
                .filter(m -> m.category() != ModuleCategory.PLATFORM)
                .sorted(java.util.Comparator.comparing(ModuleManifest::id))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantModuleView> tenantModules(UUID tenantId) {
        return tenantModules(requireTenant(tenantId).tenantSchema());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantModuleView> tenantModules(String tenantSchema) {
        Map<String, ModuleState> states = registry.moduleStates(tenantSchema);
        Map<String, StoredRow> rows = store.findStoredRows(tenantSchema);
        List<TenantModuleView> views = new ArrayList<>();
        for (ModuleManifest module : catalogue()) {
            StoredRow row = module.isCore() ? null : rows.get(module.id());
            views.add(new TenantModuleView(module.id(), module.displayName(),
                    states.getOrDefault(module.id(), ModuleState.DISABLED),
                    row != null ? row.status() : null, row != null ? row.expiresAt() : null,
                    row != null ? row.graceUntil() : null, row != null ? row.provisionedVersion() : null,
                    row != null ? row.origin() : null));
        }
        return List.copyOf(views);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The per-tenant lock {@code module-lifecycle:<tenant UUID>}; a null id is refused without any statement. */
    private void lockTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND, "Entreprise introuvable");
        }
        locks.lock(LOCK_PREFIX + tenantId);
    }

    private TenantRef requireTenant(UUID tenantId) {
        return store.findTenant(tenantId).orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND,
                "Entreprise introuvable"));
    }

    /** An installed module a tenant can hold; unknown, blank or {@code platform} ids → {@code MODULE_NOT_FOUND}. */
    private ModuleManifest requireTenantModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            throw new DomainException(ErrorCode.MODULE_NOT_FOUND, "Module introuvable");
        }
        return catalog.find(moduleId)
                .filter(m -> m.category() != ModuleCategory.PLATFORM)
                .orElseThrow(() -> new DomainException(ErrorCode.MODULE_NOT_FOUND, "Module introuvable"));
    }

    /** End of a trial requested without a date: now + {@link #REACTIVATION_TRIAL} (column precision). */
    private static Instant reactivationTrialEnd(Instant now) {
        return now.plus(REACTIVATION_TRIAL).truncatedTo(ChronoUnit.MICROS);
    }

    private static TenantModuleStatus requestedStatus(ActivationRequest request) {
        TenantModuleStatus status = request != null && request.status() != null ? request.status()
                : TenantModuleStatus.ACTIVE;
        if (status != TenantModuleStatus.ACTIVE && status != TenantModuleStatus.TRIAL) {
            throw invalid("status", "Statut invalide : choisissez Actif ou Essai");
        }
        return status;
    }

    private static Instant requestedExpiry(ActivationRequest request, Instant now) {
        Instant expiresAt = request != null ? request.expiresAt() : null;
        if (expiresAt == null) {
            return null;
        }
        if (!expiresAt.isAfter(now)) {
            throw invalid("expiresAt", "La date de fin doit être dans le futur");
        }
        if (expiresAt.isAfter(now.atZone(java.time.ZoneOffset.UTC).plusYears(MAX_EXPIRY_YEARS).toInstant())) {
            throw invalid("expiresAt", "La date de fin ne peut pas dépasser " + MAX_EXPIRY_YEARS + " ans");
        }
        return expiresAt.truncatedTo(ChronoUnit.MICROS); // PostgreSQL precision: stored value compares equal
    }

    private static DomainException invalid(String field, String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }

    private String displayName(String moduleId) {
        return catalog.find(moduleId).map(ModuleManifest::displayName).orElse("Module inconnu");
    }

    /** Drops the registry request cache once the transaction is over (committed or rolled back). */
    private void registerEviction(String schema) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    registry.evict(schema);
                }
            });
        }
    }

    /** Runs {@code action} after commit (immediately without a transaction); never fails the committed call. */
    private void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("Module lifecycle: after-commit action failed ({})", e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    // The entry lands in the tenant's own history (the owner reads it): no
    // tenant code / id in it — the owner knows its company (Story 13.8b).
    private void recordActivated(TenantRef tenant, Actor actor, Written w) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("module", w.module().displayName());
        after.put("statut", w.status() == TenantModuleStatus.TRIAL ? "Essai" : "Actif");
        if (w.expiresAt() != null) {
            after.put("finAbonnement", FR_DATE.format(w.expiresAt().atZone(KeevoZones.WAT)));
        }
        record(tenant, actor, ACTION_ACTIVATED, w.rowId(), w.module().displayName(), w.module().id(), after, null);
    }

    private void recordDeactivated(TenantRef tenant, Actor actor, UUID rowId, ModuleManifest module,
                                   boolean forced) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("module", module.displayName());
        after.put("statut", "Désactivé");
        after.put("donnees", "Conservées");
        if (forced) {
            after.put("motif", "Désactivation forcée : la mise hors service du module a échoué");
        }
        record(tenant, actor, ACTION_DEACTIVATED, rowId, module.displayName(), module.id(), after,
                forced ? KIND_FORCED : null);
    }

    /** Story 16.4 — « Pack appliqué »: the pack's name, the modules the call activated, the requested terms. */
    private void recordBundleApplied(TenantRef tenant, Actor actor, String bundleName, List<String> activated,
                                     ActivationRequest request) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("pack", bundleName);
        after.put("modulesActives", String.join(", ", activated));
        after.put("statut", request != null && request.status() == TenantModuleStatus.TRIAL ? "Essai" : "Actif");
        if (request != null && request.expiresAt() != null) {
            after.put("finAbonnement", FR_DATE.format(request.expiresAt().atZone(KeevoZones.WAT)));
        }
        UUID entityId = UUID.nameUUIDFromBytes(("bundle:" + bundleName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        log.info("[ADMIN AUDIT] Bundle applied: actor={} tenant={} bundle={} activated={}", actor.userId(),
                tenant.code(), bundleName, activated);
        record(tenant, actor, ACTION_ACTIVATED, entityId, bundleName, "bundle", after, KIND_BUNDLE);
    }

    // ── history (Story 16.4) ────────────────────────────────────────────────

    @Override
    public List<HistoryEntry> history(UUID tenantId) {
        TenantRef tenant = requireTenant(tenantId);
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenant.tenantSchema());
        try {
            // the module actions only, filtered in the queries, then merged newest first and limited
            List<AuditPort.AuditEntryRecord> records = new ArrayList<>();
            for (String action : List.of(ACTION_ACTIVATED, ACTION_DEACTIVATED)) {
                records.addAll(audit.search(new AuditPort.AuditQuery(null, null, null, null, action, ENTITY_TYPE,
                        null), null, 0, HISTORY_SIZE).entries());
            }
            records.sort(java.util.Comparator.comparing(AuditPort.AuditEntryRecord::occurredAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
            List<HistoryEntry> entries = new ArrayList<>();
            for (AuditPort.AuditEntryRecord entry : records.subList(0, Math.min(records.size(), HISTORY_SIZE))) {
                String kind = kindOf(entry.metadata());
                String label = historyLabel(entry.action(), kind);
                if (label == null) {
                    continue;
                }
                entries.add(new HistoryEntry(entry.occurredAt(), label, entry.entityLabel(),
                        historyDetail(entry.action(), kind, entry.valueAfter()), entry.actorName(),
                        entry.actorPhone()));
            }
            return List.copyOf(entries);
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenant(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    /** French label of a module audit entry in the console history; {@code null}: not a module lifecycle entry. */
    static String historyLabel(String action, String kind) {
        if (ACTION_ACTIVATED.equals(action)) {
            return KIND_BUNDLE.equals(kind) ? "Pack appliqué" : "Module activé";
        }
        if (ACTION_DEACTIVATED.equals(action)) {
            return KIND_FORCED.equals(kind) ? "Désactivation forcée" : "Module désactivé";
        }
        return null;
    }

    /** The {@code kind} of the audit metadata ({@code null}: none). */
    private static String kindOf(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode kind = JSON.readTree(metadata).get("kind");
            return kind != null && kind.isTextual() ? kind.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** A French summary of an entry's stored details (never ids); {@code null} when there is nothing to say. */
    static String historyDetail(String action, String kind, String valueAfter) {
        Map<String, Object> after;
        try {
            after = valueAfter == null ? Map.of()
                    : JSON.readValue(valueAfter, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        boolean bundle = ACTION_ACTIVATED.equals(action) && KIND_BUNDLE.equals(kind);
        if (bundle && after.get("modulesActives") != null) {
            parts.add("Modules activés : " + after.get("modulesActives"));
        }
        if (ACTION_ACTIVATED.equals(action)) {
            Object statut = after.get("statut");
            Object end = after.get("finAbonnement");
            if (statut != null) {
                parts.add(end != null ? statut + " jusqu'au " + end : String.valueOf(statut));
            }
        } else {
            if (after.get("motif") != null) {
                parts.add(String.valueOf(after.get("motif")));
            }
            parts.add("Données conservées");
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /** Writes the entry into the tenant's own audit log (the audit adapter routes by {@link TenantContext}). */
    private void record(TenantRef tenant, Actor actor, String action, UUID rowId, String label, String logId,
                        Map<String, Object> after, String kind) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("actorLabel", ACTOR_LABEL);
        if (kind != null) {
            metadata.put("kind", kind);
        }
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenant.tenantSchema());
        try {
            audit.record(new AuditPort.AuditEntry(actor.userId(), tenant.tenantSchema(), action, ENTITY_TYPE, rowId,
                    null, json(after), null, label, null, "ONLINE",
                    json(metadata)));
        } catch (RuntimeException e) {
            // the change is committed: the call succeeds, but a lost audit entry must be visible
            log.error("Module lifecycle: audit entry {} for '{}' of tenant {} NOT written ({})", action,
                    logId, tenant.code(), e.getClass().getSimpleName());
        } finally {
            if (previous != null) {
                TenantContext.setCurrentTenant(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit payload not serialisable", e);
        }
    }
}
