package com.keevo.shared.module.application;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleBackfill.BackfillReport;
import com.keevo.shared.module.ModuleBackfill.Failure;
import com.keevo.shared.module.ModuleBackfill.TenantChange;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.PlanProfileAdmin;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.PlanModuleRowSync.SyncResult;
import com.keevo.shared.module.application.port.PlanProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
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
 * subscription) get their {@code PLAN} rows re-synced ({@link PlanModuleRowSync#syncIfPlanType}, one transaction per tenant, a
 * failure reported by tenant code and the loop going on). Every change is logged {@code [ADMIN AUDIT]} with the
 * actor, the plan type and the profile before/after.
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

    public PlanProfileAdminService(ModuleCatalog catalog, PlanProfiles profiles, PlanProfileStore store,
                                   PlanModuleRowSync rowSync, PlanModuleRowStore rows,
                                   PlatformTransactionManager transactionManager) {
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
        for (PlanType type : PlanType.values()) {
            views.add(view(profiles.profile(type)));
        }
        return views;
    }

    @Override
    public UpdateResult update(PlanType planType, Integer maxStores, Integer maxProducts, Integer maxEmployees,
                               Set<String> modules, UUID actorId) {
        if (planType == null) {
            throw invalid("offre inconnue");
        }
        requireLimit(maxStores, "le nombre de boutiques");
        requireLimit(maxProducts, "le nombre de produits");
        requireLimit(maxEmployees, "le nombre d'employés");
        if (modules == null) {
            throw invalid("indiquez les modules inclus dans l'offre (liste vide si aucun)");
        }
        Set<String> granted = validModules(modules);
        // explicit decision for every installed paid module (a module shipped later follows the plan's default)
        Set<String> withheld = new TreeSet<>(paidModules());
        withheld.removeAll(granted);
        PlanProfile after = PlanProfile.of(planType, maxStores, maxProducts, maxEmployees, granted, withheld);

        PlanProfile before = tx.execute(status -> {
            Optional<PlanProfile> current = store.findForUpdate(planType); // row lock: edits serialise
            store.save(after, actorId);
            return current.orElse(null);
        });
        boolean compositionChanged = !effective(before != null ? before : PlanProfile.fromEnum(planType))
                .equals(effective(after));
        log.info("[ADMIN AUDIT] Plan profile changed: actor={} plan={} before={} after={}", actorId, planType,
                describe(before), describe(after));

        BackfillReport resync = compositionChanged ? resync(planType) : null;
        return new UpdateResult(view(after), compositionChanged, resync);
    }

    /** Re-syncs the {@code PLAN} rows of every live tenant whose plan has this type. */
    private BackfillReport resync(PlanType planType) {
        int processed = 0;
        int skipped = 0;
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int preserved = 0;
        List<TenantChange> changes = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (LiveTenant tenant : rows.liveTenants(planType.name())) {
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

    private static void requireLimit(Integer limit, String what) {
        if (limit != null && limit <= 0) {
            throw invalid(what + " maximal doit être supérieur à zéro (laissez vide pour illimité)");
        }
    }

    private ProfileView view(PlanProfile profile) {
        List<ModuleRef> granted = catalog.manifests().stream()
                .filter(PlanModuleRows::planDerived)
                .filter(m -> profile.grants(m.id()))
                .sorted(Comparator.comparing(ModuleManifest::displayName))
                .map(m -> new ModuleRef(m.id(), m.displayName()))
                .toList();
        return new ProfileView(profile.planType().name(), label(profile.planType()), profile.maxStores(),
                profile.maxProducts(), profile.maxEmployees(), granted, profile.fallback());
    }

    static String label(PlanType type) {
        return switch (type) {
            case FREE -> "Gratuit";
            case PREMIUM_TRIAL -> "Essai Premium";
            case PREMIUM -> "Premium";
        };
    }

    private static String describe(PlanProfile p) {
        if (p == null) {
            return "none";
        }
        return "{stores=" + limit(p.maxStores()) + ", products=" + limit(p.maxProducts()) + ", employees="
                + limit(p.maxEmployees()) + ", granted=" + new TreeSet<>(p.modules()) + ", withheld="
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
