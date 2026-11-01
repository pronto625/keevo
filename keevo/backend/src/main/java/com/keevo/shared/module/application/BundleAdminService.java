package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.BundleAdmin;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.application.port.BundleStore;
import com.keevo.shared.module.application.port.BundleStore.StoredBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link BundleAdmin} of the core (Story 16.2). Definitions are validated against the installed manifests, then saved
 * in one transaction; an application re-validates the stored modules (the catalogue may have changed since) and hands
 * them to {@link ModuleLifecycle#activateModules}, which runs in its own transaction under the tenant lock (this
 * service opens none around it, so the lock stays the first statement). Every change is logged {@code [ADMIN AUDIT]}.
 */
@Service
public class BundleAdminService implements BundleAdmin {

    private static final Logger log = LoggerFactory.getLogger(BundleAdminService.class);

    /** Kebab-case slug, same rule as module ids (and the {@code bundle_definitions} check constraint). */
    static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    static final int MAX_ID = 50;
    static final int MAX_NAME = 100;
    static final int MAX_DESCRIPTION = 500;

    private final ModuleCatalog catalog;
    private final BundleStore store;
    private final ModuleLifecycle lifecycle;
    private final TransactionTemplate tx;

    public BundleAdminService(ModuleCatalog catalog, BundleStore store, ModuleLifecycle lifecycle,
                              PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.store = store;
        this.lifecycle = lifecycle;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<BundleView> list() {
        return store.findAll().stream().map(this::view).toList();
    }

    @Override
    public SaveResult save(String bundleId, String name, String description, List<String> modules, UUID actorId) {
        if (!validId(bundleId)) {
            throw invalid("l'identifiant du pack doit contenir uniquement des minuscules, des chiffres et des tirets "
                    + "(exemple : pack-pro)");
        }
        String cleanName = name != null ? name.strip() : "";
        if (cleanName.isEmpty()) {
            throw invalid("indiquez le nom du pack");
        }
        if (cleanName.length() > MAX_NAME) {
            throw invalid("le nom du pack ne doit pas dépasser " + MAX_NAME + " caractères");
        }
        String cleanDescription = description != null && !description.isBlank() ? description.strip() : null;
        if (cleanDescription != null && cleanDescription.length() > MAX_DESCRIPTION) {
            throw invalid("la description du pack ne doit pas dépasser " + MAX_DESCRIPTION + " caractères");
        }
        List<String> ids = validModules(modules);
        StoredBundle bundle = new StoredBundle(bundleId, cleanName, cleanDescription, ids);
        boolean created = Boolean.TRUE.equals(tx.execute(status -> store.save(bundle, actorId)));
        log.info("[ADMIN AUDIT] Bundle {}: actor={} bundle={} modules={}", created ? "created" : "replaced", actorId,
                bundleId, ids);
        return new SaveResult(view(bundle), created);
    }

    @Override
    public void delete(String bundleId, UUID actorId) {
        if (!validId(bundleId) || !Boolean.TRUE.equals(tx.execute(status -> store.delete(bundleId)))) {
            throw notFound();
        }
        log.info("[ADMIN AUDIT] Bundle deleted: actor={} bundle={} (tenants keep their modules)", actorId, bundleId);
    }

    @Override
    public ApplyResult apply(UUID tenantId, String bundleId, ModuleLifecycle.ActivationRequest request,
                             ModuleLifecycle.Actor actor) {
        if (!validId(bundleId)) {
            throw notFound();
        }
        StoredBundle bundle = store.find(bundleId).orElseThrow(BundleAdminService::notFound);
        if (!applicable(bundle)) {
            throw new DomainException(ErrorCode.BUNDLE_NOT_APPLICABLE, "Le pack « " + bundle.name()
                    + " » contient un module qui n'est plus disponible. Modifiez-le avant de l'appliquer.",
                    Map.of("name", bundle.name()));
        }
        List<String> modules = bundle.modules();
        ModuleLifecycle.ModulesActivationResult result = lifecycle.activateModules(tenantId, modules, request, actor);
        log.info("[ADMIN AUDIT] Bundle applied: actor={} tenant={} bundle={} activated={}", actor.userId(), tenantId,
                bundleId, result.activated().stream().map(ModuleLifecycle.ModuleRef::moduleId).toList());
        return new ApplyResult(bundle.name(), result.activated(), result.alreadyActive());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static boolean validId(String bundleId) {
        return bundleId != null && bundleId.length() <= MAX_ID && ID.matcher(bundleId).matches();
    }

    /** Installed, non-core, non-platform, no duplicates, at least one; the ids in the requested order. */
    private List<String> validModules(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw invalid("choisissez au moins un module");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : requested) {
            if (id == null || id.isBlank()) {
                throw invalid("un des modules demandés n'existe pas");
            }
            ModuleManifest module = catalog.find(id.strip())
                    .orElseThrow(() -> invalid("un des modules demandés n'existe pas"));
            if (module.isCore() || module.category() == ModuleCategory.CORE) {
                throw invalid("« " + module.displayName() + " » fait partie du socle, toujours actif : "
                        + "il n'a pas sa place dans un pack");
            }
            if (module.category() == ModuleCategory.PLATFORM) {
                throw invalid("« " + module.displayName() + " » n'est pas un module proposé aux commerçants");
            }
            if (!ids.add(module.id())) {
                throw invalid("« " + module.displayName() + " » figure plusieurs fois dans le pack");
            }
        }
        return List.copyOf(ids);
    }

    /** Whether a module can be part of a bundle: installed, neither core nor platform. */
    private boolean available(String moduleId) {
        return catalog.find(moduleId).filter(m -> !m.isCore() && m.category() != ModuleCategory.CORE
                && m.category() != ModuleCategory.PLATFORM).isPresent();
    }

    /** Whether every module of a stored bundle is still available (the catalogue may have changed since). */
    private boolean applicable(StoredBundle bundle) {
        return !bundle.modules().isEmpty() && bundle.modules().stream().allMatch(this::available);
    }

    private BundleView view(StoredBundle bundle) {
        List<ModuleLifecycle.ModuleRef> modules = new ArrayList<>();
        for (String id : bundle.modules()) {
            modules.add(new ModuleLifecycle.ModuleRef(id, available(id)
                    ? catalog.find(id).orElseThrow().displayName() : "Module indisponible"));
        }
        return new BundleView(bundle.id(), bundle.name(), bundle.description(), modules, applicable(bundle));
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.BUNDLE_NOT_FOUND, "Pack introuvable");
    }

    private static DomainException invalid(String reason) {
        String sentence = Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
        return new DomainException(ErrorCode.BUNDLE_INVALID, sentence, Map.of("reason", reason));
    }
}
