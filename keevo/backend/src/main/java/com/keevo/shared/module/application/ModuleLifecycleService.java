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
import org.springframework.transaction.annotation.Transactional;
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
 * the requested status and end date, which is then mandatory ({@code TRIAL} always needs one; at most 10 years);
 * {@code grace_until = expires_at + 7 days}; {@code activated_at = now} only on an inactive → active transition
 * (kept otherwise), {@code disabled_at = null}. Deactivation upserts {@code DISABLED} + {@code disabled_at}
 * and never deletes a row (a free module without a row is active). A provisioning failure rolls the whole
 * transaction back (rows as before — but tables created by migrations that already ran stay, Flyway commits on its
 * own connection) and answers {@code MODULE_PROVISIONING_FAILED} without technical detail.
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
    /** Farthest end date accepted (a typo must not grant decades, nor overflow the column). */
    static final int MAX_EXPIRY_YEARS = 10;
    static final String ACTION_ACTIVATED = "MODULE_ACTIVATED";
    static final String ACTION_DEACTIVATED = "MODULE_DEACTIVATED";
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

    /** Without module beans (no hook called) — tests and catalogues without converted modules. */
    public ModuleLifecycleService(ModuleCatalog catalog, ModuleRegistry registry, DependencyResolver resolver,
                                  ModuleProvisioner provisioner, TenantModuleLifecyclePort store,
                                  TransactionLockPort locks, AuditPort audit, Clock clock) {
        this(catalog, registry, resolver, provisioner, store, locks, audit, clock,
                KeevoModuleDirectory.empty(catalog));
    }

    @Autowired
    public ModuleLifecycleService(ModuleCatalog catalog, ModuleRegistry registry, DependencyResolver resolver,
                                  ModuleProvisioner provisioner, TenantModuleLifecyclePort store,
                                  TransactionLockPort locks, AuditPort audit, Clock clock,
                                  KeevoModuleDirectory modules) {
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
        TenantModuleStatus status = requestedStatus(request);
        Instant expiresAt = requestedExpiry(request, now);
        if (status == TenantModuleStatus.TRIAL && expiresAt == null) {
            throw invalid("expiresAt", "Une date de fin est requise pour un essai");
        }
        String schema = tenant.tenantSchema();
        registry.evict(schema); // never plan from rows read before the lock
        registerEviction(schema);

        if (target.isCore()) { // always enabled, never a row
            return new ActivationResult(List.of(), TenantModuleStatus.ACTIVE, null, null);
        }
        ActivationPlan plan = (actor.superAdmin() ? grantingResolver : resolver).planActivation(schema, target.id());
        if (plan.blocked()) {
            throw blocked(plan);
        }
        Map<String, StoredRow> rows = store.findStoredRows(schema);
        List<String> toWrite = new ArrayList<>(plan.moduleIds());
        if (!toWrite.contains(target.id())
                && (registry.moduleState(schema, target.id()) != ModuleState.ENABLED
                    || (!target.free() && changes(rows.get(target.id()), status, expiresAt)))) {
            toWrite.add(target.id()); // renewal of a read-only module, or a new status/expiry of a paid one
        }
        checkExpiryRules(target, toWrite, status, expiresAt);
        if (toWrite.isEmpty()) {
            // already enabled: make sure its tables exist — and those of its dependencies active by the free rule
            // (no row): such modules are active but may never have been provisioned. No-op when up to date.
            for (String id : dependencyOrder(plan.alreadyActive())) {
                if (!id.equals(target.id()) && !rows.containsKey(id)) {
                    provision(tenant, catalog.find(id).orElseThrow());
                }
            }
            provision(tenant, target);
            registry.evict(schema);
            return result(List.of(), store.findStoredRows(schema).get(target.id()));
        }

        List<Written> written = new ArrayList<>();
        for (String id : toWrite) {
            ModuleManifest module = catalog.find(id).orElseThrow();
            // a free module never gets a status other than ACTIVE nor an expiry; a paid one (target or dependency)
            // gets the requested status and end date
            TenantModuleStatus stepStatus = module.free() ? TenantModuleStatus.ACTIVE : status;
            Instant stepExpiry = module.free() ? null : expiresAt;
            Instant grace = stepExpiry != null ? stepExpiry.plus(GRACE) : null;
            // activated_at is only set on an inactive → active transition
            boolean wasActive = registry.moduleState(schema, id) != ModuleState.DISABLED;
            UUID rowId = store.upsertActivated(tenant.id(), id, stepStatus,
                    module.free() ? TIER_FREE : TIER_PAID, now, wasActive, stepExpiry, grace);
            registry.evict(schema);
            provision(tenant, module);
            if (!wasActive) { // only an inactive → active transition (not a renewal / status change)
                onActivate(tenant, actor, module);
            }
            written.add(new Written(rowId, module, stepStatus, stepExpiry));
        }
        if (!toWrite.contains(target.id())) {
            provision(tenant, target); // repair plan (dependencies only): the target's tables too
        }
        registry.evict(schema);

        StoredRow targetRow = store.findStoredRows(schema).get(target.id());
        List<ModuleRef> activated = written.stream()
                .map(w -> new ModuleRef(w.module().id(), w.module().displayName())).toList();
        afterCommit(() -> {
            log.info("[ADMIN AUDIT] Modules activated: actor={} tenant={} modules={}", actor.userId(), tenant.code(),
                    written.stream().map(w -> w.module().id()).toList());
            written.forEach(w -> recordActivated(tenant, actor, w));
        });
        return result(activated, targetRow);
    }

    /** The target's stored state; a target without a row (free rule) is ACTIVE without expiry nor version. */
    private static ActivationResult result(List<ModuleRef> activated, StoredRow row) {
        return row == null ? new ActivationResult(activated, TenantModuleStatus.ACTIVE, null, null)
                : new ActivationResult(activated, row.status(), row.expiresAt(), row.provisionedVersion());
    }

    /**
     * Expiry rules (orchestrator decision, 13.8a review): every paid module the call writes needs an explicit end
     * date; a free module never gets one. {@code TRIAL} / {@code expiresAt} with a free target are refused unless the
     * call writes a paid dependency, to which they then apply (the free target itself is written ACTIVE, no end).
     */
    private void checkExpiryRules(ModuleManifest target, List<String> toWrite, TenantModuleStatus status,
                                  Instant expiresAt) {
        List<String> paid = toWrite.stream()
                .filter(id -> !catalog.find(id).orElseThrow().free()).toList();
        if (!paid.isEmpty() && expiresAt == null) {
            String names = paid.stream().map(id -> "« " + displayName(id) + " »").collect(Collectors.joining(", "));
            throw invalid("expiresAt", "Une date de fin est requise pour un module payant (" + names + ")");
        }
        if (target.free() && paid.isEmpty() && (expiresAt != null || status != TenantModuleStatus.ACTIVE)) {
            throw invalid(expiresAt != null ? "expiresAt" : "status", "Le module « " + target.displayName()
                    + " » est gratuit : il n'a ni date de fin ni période d'essai");
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
    private static boolean changes(StoredRow row, TenantModuleStatus status, Instant expiresAt) {
        if (row == null) {
            return status != TenantModuleStatus.ACTIVE || expiresAt != null;
        }
        return row.status() != status || !Objects.equals(row.expiresAt(), expiresAt);
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

    @Override
    @Transactional
    public DeactivationResult deactivate(UUID tenantId, String moduleId, Actor actor) {
        Objects.requireNonNull(actor, "actor");
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
        DeactivationCheck check = resolver.checkDeactivation(schema, target.id());
        if (check.blocked()) {
            List<String> names = check.dependents().stream().map(this::displayName).toList();
            String joined = names.stream().map(n -> "« " + n + " »").collect(Collectors.joining(", "));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("dependents", joined);
            details.put("modules", names);
            throw new DomainException(ErrorCode.MODULE_HAS_DEPENDENTS,
                    "Désactivation impossible : ce module est utilisé par " + joined, details);
        }
        UUID rowId = store.upsertDisabled(tenant.id(), target.id(), target.free() ? TIER_FREE : TIER_PAID, now);
        registry.evict(schema);
        onDeactivate(tenant, actor, target);
        afterCommit(() -> {
            log.info("[ADMIN AUDIT] Module deactivated: actor={} tenant={} module={}", actor.userId(), tenant.code(),
                    target.id());
            recordDeactivated(tenant, actor, rowId, target);
        });
        return new DeactivationResult(target.id(), target.displayName(), TenantModuleStatus.DISABLED);
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
                    row != null ? row.graceUntil() : null, row != null ? row.provisionedVersion() : null));
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
        record(tenant, actor, ACTION_ACTIVATED, w.rowId(), w.module(), after);
    }

    private void recordDeactivated(TenantRef tenant, Actor actor, UUID rowId, ModuleManifest module) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("module", module.displayName());
        after.put("statut", "Désactivé");
        after.put("donnees", "Conservées");
        record(tenant, actor, ACTION_DEACTIVATED, rowId, module, after);
    }

    /** Writes the entry into the tenant's own audit log (the audit adapter routes by {@link TenantContext}). */
    private void record(TenantRef tenant, Actor actor, String action, UUID rowId, ModuleManifest module,
                        Map<String, Object> after) {
        String previous = TenantContext.getCurrentTenant();
        TenantContext.setCurrentTenant(tenant.tenantSchema());
        try {
            audit.record(new AuditPort.AuditEntry(actor.userId(), tenant.tenantSchema(), action, ENTITY_TYPE, rowId,
                    null, json(after), null, module.displayName(), null, "ONLINE",
                    json(Map.of("actorLabel", ACTOR_LABEL))));
        } catch (RuntimeException e) {
            // the change is committed: the call succeeds, but a lost audit entry must be visible
            log.error("Module lifecycle: audit entry {} for module '{}' of tenant {} NOT written ({})", action,
                    module.id(), tenant.code(), e.getClass().getSimpleName());
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
