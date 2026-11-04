package com.keevo.shared.module.application;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.job.AdminJobs;
import com.keevo.shared.job.ProgressListener;
import com.keevo.shared.module.ModuleBackfill.BackfillReport;
import com.keevo.shared.module.ModuleBackfill.Failure;
import com.keevo.shared.module.ModuleBackfill.TenantChange;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.PlanProfileAdmin;
import com.keevo.shared.module.PlanProfileAdmin.LimitPatch;
import com.keevo.shared.module.PlanProfileAdmin.QuotaPatch;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.PlanModuleRowSync.SyncResult;
import com.keevo.shared.module.application.port.PlanProfileStore;
import com.keevo.shared.module.application.port.PlanQuotaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link PlanProfileAdmin} of the core (Story 16.8). The profile is validated, then saved in its own transaction
 * (row locked, so two edits serialise) with an explicit decision for every installed paid module; when the
 * effective composition changed, the live tenants whose public plan type is that type (re-checked on their own
 * subscription) get their {@code PLAN} rows re-synced by a {@code PROFILE_RESYNC} background job (Story 16.4) ({@link PlanModuleRowSync#syncIfPlanType}, one transaction per tenant, a
 * failure reported by tenant code and the loop going on). Every change is logged {@code [ADMIN AUDIT]} with the
 * actor, the plan type and the profile before/after.
 *
 * <p>Story 19.8 — the plan's usage quotas ({@code public.plan_quotas}) are shown with the profile and, when given,
 * replaced in the same transaction (one query for the quotas of every plan on {@link #list()}).
 */
@Service
public class PlanProfileAdminService implements PlanProfileAdmin {

    private static final Logger log = LoggerFactory.getLogger(PlanProfileAdminService.class);

    private final ModuleCatalog catalog;
    private final PlanProfiles profiles;
    private final PlanProfileStore store;
    private final PlanModuleRowSync rowSync;
    private final PlanModuleRowStore rows;
    private final TransactionTemplate tx;
    private final AdminJobs jobs;
    private final PlanCatalog plans;
    private final PlanQuotaStore quotas;

    /** With the seeded plan catalogue (tests). */
    public PlanProfileAdminService(ModuleCatalog catalog, PlanProfiles profiles, PlanProfileStore store,
                                   PlanModuleRowSync rowSync, PlanModuleRowStore rows,
                                   PlatformTransactionManager transactionManager, AdminJobs jobs) {
        this(catalog, profiles, store, rowSync, rows, transactionManager, jobs, PlanCatalog.SEED);
    }

    /** Without usage quotas (tests). */
    public PlanProfileAdminService(ModuleCatalog catalog, PlanProfiles profiles, PlanProfileStore store,
                                   PlanModuleRowSync rowSync, PlanModuleRowStore rows,
                                   PlatformTransactionManager transactionManager, AdminJobs jobs, PlanCatalog plans) {
        this(catalog, profiles, store, rowSync, rows, transactionManager, jobs, plans, PlanQuotaStore.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlanProfileAdminService(ModuleCatalog catalog, PlanProfiles profiles, PlanProfileStore store,
                                   PlanModuleRowSync rowSync, PlanModuleRowStore rows,
                                   PlatformTransactionManager transactionManager, AdminJobs jobs, PlanCatalog plans,
                                   PlanQuotaStore quotas) {
        this.quotas = quotas;
        this.plans = plans;
        this.jobs = jobs;
        this.catalog = catalog;
        this.profiles = profiles;
        this.store = store;
        this.rowSync = rowSync;
        this.rows = rows;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<ProfileView> list() {
        List<ProfileView> views = new ArrayList<>();
        // Story 19.7a: every plan of the catalogue, in display order, profiles read in one query
        List<PlanType> codes = plans.all().stream().map(PlanDefinition::code).toList();
        Map<PlanType, List<PlanQuota>> quotasByPlan = quotas.quotas(codes); // 19.8: one query
        profiles.profiles(codes).values()
                .forEach(p -> views.add(view(p, quotasByPlan.getOrDefault(p.planType(), List.of()))));
        return views;
    }

    @Override
    public UpdateResult update(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                               LimitPatch maxEmployeesPerStore, Set<String> modules, QuotaPatch quotaPatch,
                               UUID actorId) {
        if (planType == null) {
            throw invalid("offre inconnue");
        }
        if (plans.find(planType).isEmpty()) { // Story 19.7a: any catalogue plan, nothing else
            throw new DomainException(ErrorCode.PLAN_PROFILE_NOT_FOUND, "Offre introuvable");
        }
        requireLimit(maxStores, "le nombre de boutiques");
        requireLimit(maxProducts, "le nombre de produits");
        requireLimit(maxEmployees, "le nombre d'employés");
        if (maxEmployeesPerStore instanceof LimitPatch.Set(Integer perStore)) {
            requireLimit(perStore, "le nombre d'employés par boutique");
        }
        if (modules == null) {
            throw invalid("indiquez les modules inclus dans l'offre (liste vide si aucun)");
        }
        Set<String> granted = validModules(modules);
        // 19.8: validated before anything is written; null = keep the stored quotas
        List<PlanQuota> newQuotas = quotaPatch instanceof QuotaPatch.Replace(List<PlanQuota> requestedQuotas)
                ? validQuotas(requestedQuotas) : null;
        // explicit decision for every installed paid module (a module shipped later follows the plan's default)
        Set<String> withheld = new TreeSet<>(paidModules());
        withheld.removeAll(granted);
        PlanProfile requested = PlanProfile.of(planType, plans.kind(planType), maxStores, maxProducts, maxEmployees,
                maxEmployeesPerStore.resolve(null), granted, withheld);

        // Story 16.4: a composition change re-syncs the plan's tenants in a background job. The change is decided
        // from the row read under its lock, and the job slot is reserved before the write: a refusal (409, a re-sync
        // still running) saves nothing. Nothing is thrown once the profile is committed.
        Map<String, Object> params = Map.of("planType", planType.name(), "plan", label(planType));
        AdminJobs.Reservation[] reserved = new AdminJobs.Reservation[1];
        PlanProfile[] before = new PlanProfile[1];
        PlanProfile[] saved = new PlanProfile[1];
        java.util.concurrent.atomic.AtomicReference<List<PlanQuota>> quotasBefore =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        try {
            tx.executeWithoutResult(status -> {
                PlanProfile stored = store.findForUpdate(planType).orElse(null); // row lock: edits serialise
                before[0] = stored;
                // 19.7b: a per-store limit not sent keeps the stored one (clients that do not know it)
                saved[0] = stored == null ? requested : requested.withMaxEmployeesPerStore(
                        maxEmployeesPerStore.resolve(stored.maxEmployeesPerStore()));
                PlanProfile after = saved[0];
                if (!effective(stored != null ? stored : profiles.profile(planType)).equals(effective(after))) {
                    reserved[0] = jobs.reserve(AdminJobs.Kind.PROFILE_RESYNC, params, actorId);
                }
                store.save(after, actorId);
                // 19.8: quotas read in this transaction (audit « before », answer for Keep), replaced when given
                quotasBefore.set(quotas.quotas(List.of(planType)).getOrDefault(planType, List.of()));
                if (newQuotas != null) {
                    quotas.replace(planType, newQuotas); // applies to the next increment
                }
            });
        } catch (RuntimeException e) {
            if (reserved[0] != null) {
                reserved[0].cancel();
            }
            throw e;
        }
        boolean compositionChanged = reserved[0] != null;
        List<PlanQuota> savedQuotas = newQuotas != null ? newQuotas : quotasBefore.get();
        log.info("[ADMIN AUDIT] Plan profile changed: actor={} plan={} before={} after={} quotasBefore={} "
                        + "quotasAfter={}", actorId, planType, describe(before[0]), describe(saved[0]),
                describe(quotasBefore.get()), newQuotas != null ? describe(newQuotas) : "unchanged");

        UUID jobId = null;
        if (compositionChanged) {
            jobId = reserved[0].id();
            try {
                reserved[0].submit(progress -> resync(planType, progress));
            } catch (RuntimeException e) {
                // the profile is committed: the job is already marked failed (French reason), the call succeeds
                log.error("Plan profile {}: re-sync job could not start ({}) — run it again by saving the profile",
                        planType, e.getClass().getSimpleName());
            }
        }
        return new UpdateResult(view(saved[0], savedQuotas), compositionChanged, jobId);
    }

    /** Re-syncs the {@code PLAN} rows of every live tenant whose plan has this type. */
    @Override
    public BackfillReport resync(PlanType planType, ProgressListener progress) {
        ProgressListener listener = progress != null ? progress : ProgressListener.NONE;
        int processed = 0;
        int skipped = 0;
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int preserved = 0;
        List<TenantChange> changes = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<LiveTenant> tenants = rows.liveTenants(planType.name());
        int total = tenants.size();
        int done = 0;
        listener.progress(0, total);
        for (LiveTenant tenant : tenants) {
            if (done > 0) {
                listener.progress(done, total);
            }
            done++;
            if (!TenantSchema.isValid(tenant.tenantSchema()) || !tenant.schemaPresent()) {
                skipped++;
                continue;
            }
            Optional<SyncResult> result;
            try {
                result = rowSync.syncIfPlanType(tenant.id(), tenant.tenantSchema(), planType.name());
            } catch (PlanModuleRowSync.PlanUnreadableException e) {
                failures.add(new Failure(tenant.code(), "Abonnement illisible : modules laissés inchangés"));
                log.warn("Plan profile re-sync: tenant {} skipped (plan unreadable)", tenant.code());
                continue;
            } catch (RuntimeException e) {
                failures.add(new Failure(tenant.code(), "Mise à jour des modules impossible : réessayez plus tard"));
                log.warn("Plan profile re-sync: rows of tenant {} not synced ({})", tenant.code(),
                        e.getClass().getSimpleName());
                continue;
            }
            if (result.isEmpty()) {
                continue; // another plan type: not affected
            }
            SyncResult r = result.get();
            processed++;
            inserted += r.inserted();
            updated += r.updated();
            unchanged += r.unchanged();
            preserved += r.preserved();
            if (r.written() > 0) {
                changes.add(new TenantChange(tenant.code(), r.inserted(), r.updated(), List.of()));
            }
        }
        listener.progress(total, total);
        log.info("Plan profile re-sync ({}): {} tenant(s) processed, {} failed — rows {} inserted, {} updated, "
                        + "{} unchanged, {} preserved", planType, processed, failures.size(), inserted, updated,
                unchanged, preserved);
        return new BackfillReport(false, processed, skipped, inserted, updated, unchanged, preserved, 0, changes,
                failures);
    }

    /** Ids of the installed paid modules. */
    private Set<String> paidModules() {
        Set<String> ids = new TreeSet<>();
        catalog.manifests().stream().filter(PlanModuleRows::planDerived).forEach(m -> ids.add(m.id()));
        return ids;
    }

    /** The installed paid modules the profile grants (explicitly or by default). */
    private Set<String> effective(PlanProfile profile) {
        Set<String> ids = new TreeSet<>();
        for (String id : paidModules()) {
            if (profile.grants(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Set<String> validModules(Set<String> requested) {
        Set<String> granted = new TreeSet<>();
        for (String id : requested) {
            if (id == null || id.isBlank()) {
                throw invalid("un des modules demandés n'existe pas");
            }
            ModuleManifest module = catalog.find(id.trim())
                    .orElseThrow(() -> invalid("un des modules demandés n'existe pas"));
            if (module.isCore()) {
                throw invalid("« " + module.displayName() + " » fait partie du socle, inclus dans toutes les offres");
            }
            if (module.category() == ModuleCategory.PLATFORM) {
                throw invalid("« " + module.displayName() + " » n'est pas un module proposé aux commerçants");
            }
            if (module.free()) {
                throw invalid("« " + module.displayName() + " » est un module gratuit, inclus dans toutes les offres");
            }
            granted.add(module.id());
        }
        return granted;
    }

    /** Story 19.8 — limit {@code >= 0} (typed by the record), at most one quota per metric and period. */
    private static List<PlanQuota> validQuotas(List<PlanQuota> requested) {
        if (requested == null) {
            throw invalid("la liste des quotas est invalide");
        }
        Set<Map.Entry<UsageMetric, QuotaPeriod>> seen = new HashSet<>();
        for (PlanQuota q : requested) {
            if (q == null) {
                throw invalid("un des quotas est invalide");
            }
            if (!seen.add(Map.entry(q.metric(), q.period()))) {
                throw invalid("un même quota est indiqué deux fois pour la même période");
            }
        }
        return List.copyOf(requested);
    }

    private static String describe(List<PlanQuota> quotas) {
        return quotas.stream().map(q -> q.metric() + "/" + q.period() + "=" + q.limit()).toList().toString();
    }

    private static void requireLimit(Integer limit, String what) {
        if (limit != null && limit <= 0) {
            throw invalid(what + " maximal doit être supérieur à zéro (laissez vide pour illimité)");
        }
    }

    private ProfileView view(PlanProfile profile, List<PlanQuota> planQuotas) {
        List<ModuleRef> granted = catalog.manifests().stream()
                .filter(PlanModuleRows::planDerived)
                .filter(m -> profile.grants(m.id()))
                .sorted(Comparator.comparing(ModuleManifest::displayName))
                .map(m -> new ModuleRef(m.id(), m.displayName()))
                .toList();
        Optional<PlanDefinition> plan = plans.find(profile.planType());
        return new ProfileView(profile.planType().name(), label(profile.planType()), profile.maxStores(),
                profile.maxProducts(), profile.maxEmployees(), granted, profile.fallback(),
                plan.map(p -> p.kind().name()).orElse(profile.kind().name()),
                plan.map(PlanDefinition::archived).orElse(false), profile.maxEmployeesPerStore(),
                planQuotas.stream().map(QuotaRef::of).toList());
    }

    /** French name of the plan, from the catalogue (Story 19.7a). */
    String label(PlanType type) {
        return plans.label(type.code(), type.code());
    }

    private static String describe(PlanProfile p) {
        if (p == null) {
            return "none";
        }
        return "{stores=" + limit(p.maxStores()) + ", products=" + limit(p.maxProducts()) + ", employees="
                + limit(p.maxEmployees()) + ", employeesPerStore=" + limit(p.maxEmployeesPerStore()) + ", granted=" + new TreeSet<>(p.modules()) + ", withheld="
                + new TreeSet<>(p.withheld()) + (p.fallback() ? ", built-in defaults" : "") + "}";
    }

    private static String limit(Integer value) {
        return value != null ? value.toString() : "unlimited";
    }

    private static DomainException invalid(String reason) {
        String sentence = Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
        return new DomainException(ErrorCode.PLAN_PROFILE_INVALID, sentence, Map.of("reason", reason));
    }
}
