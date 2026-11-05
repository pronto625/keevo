package com.keevo.shared.module.application;

import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.module.ModuleOwnerActivationChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.OwnerModuleActivation;
import com.keevo.shared.module.application.port.OwnerModuleActivationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link OwnerModuleActivation} of the core (Story 19.13) — a Command serialized per tenant: the transaction-scoped
 * lock {@code module-owner-activation:<tenant UUID>} is taken first, then the tenant's owner rows are read once (fresh,
 * under the lock), the dependency rules are checked on the manifests' {@code requires}, and the choice is upserted.
 * The entitlement (« in plan ») comes from the registry's cached lookup without the owner layer
 * ({@link ModuleRegistry#entitledStates}); the owner layer itself is read from the store under the lock.
 *
 * <p>Never touches {@code tenant_modules}, never raises a licence event, never purges data nor calls module hooks:
 * the owner choice is orthogonal to the plan (« usable = entitled ∧ activated », {@code ModuleStateRules}).
 *
 * <p>Complexity: lock + one PK-prefix read + one upsert; the dependency checks are O(installed modules).
 */
@Service
public class OwnerModuleActivationService implements OwnerModuleActivation {

    private static final Logger log = LoggerFactory.getLogger(OwnerModuleActivationService.class);

    static final String LOCK_PREFIX = "module-owner-activation:";

    private final ModuleCatalog catalog;
    private final ModuleRegistry registry;
    private final OwnerModuleActivationStore store;
    private final TransactionLockPort locks;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OwnerModuleActivationService(ModuleCatalog catalog, ModuleRegistry registry,
                                        OwnerModuleActivationStore store, TransactionLockPort locks,
                                        ApplicationEventPublisher events, Clock clock) {
        this.catalog = catalog;
        this.registry = registry;
        this.store = store;
        this.locks = locks;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean setActivation(String tenantSchema, String moduleId, boolean activated, UUID actorId) {
        String schema = TenantSchema.validate(tenantSchema);
        ModuleManifest module = requireTenantModule(moduleId);
        if (!module.ownerCanToggle()) {
            throw DomainException.userFacing(ErrorCode.MODULE_NOT_TOGGLEABLE, "Ce module ne peut pas être désactivé");
        }
        UUID tenantId = store.findTenantId(schema)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND, "Entreprise introuvable"));
        locks.lock(LOCK_PREFIX + tenantId);

        Map<String, ModuleState> entitled = registry.entitledStates(schema);
        if (!inPlan(entitled, module.id())) {
            throw DomainException.userFacing(ErrorCode.MODULE_NOT_IN_PLAN,
                    "Ce module n'est pas compris dans votre plan : voyez Mon plan");
        }
        Set<String> ownerDisabled = store.findActivations(tenantId).entrySet().stream()
                .filter(e -> !e.getValue()).map(Map.Entry::getKey).collect(Collectors.toSet());
        boolean current = !ownerDisabled.contains(module.id());
        if (current == activated) {
            return false;
        }
        if (activated) {
            List<String> blocking = module.requires().stream()
                    .filter(ownerDisabled::contains)
                    .map(catalog::find).flatMap(java.util.Optional::stream)
                    .filter(ModuleManifest::ownerCanToggle)
                    .sorted(Comparator.comparing(ModuleManifest::id))
                    .map(ModuleManifest::displayName).toList();
            if (!blocking.isEmpty()) {
                String names = String.join(", ", blocking);
                throw DomainException.userFacing(ErrorCode.MODULE_REQUIREMENT_DISABLED, "Activez d'abord : " + names,
                        Map.of("requirements", names));
            }
        } else {
            List<String> dependents = catalog.manifests().stream()
                    .filter(m -> !m.id().equals(module.id()) && m.requires().contains(module.id()))
                    .filter(m -> m.category() != ModuleCategory.PLATFORM && inPlan(entitled, m.id()))
                    .filter(m -> !(m.ownerCanToggle() && ownerDisabled.contains(m.id())))
                    .sorted(Comparator.comparing(ModuleManifest::id))
                    .map(ModuleManifest::displayName).toList();
            if (!dependents.isEmpty()) {
                String names = String.join(", ", dependents);
                throw DomainException.userFacing(ErrorCode.MODULE_HAS_DEPENDENTS, "Désactivez d'abord : " + names,
                        Map.of("dependents", names));
            }
        }
        Instant now = clock.instant();
        store.upsert(tenantId, module.id(), activated, actorId, now);
        evictAfterCommit(schema);
        events.publishEvent(new ModuleOwnerActivationChangedEvent(schema, tenantId, module.id(), module.displayName(),
                activated, actorId, now));
        log.info("Owner module activation: module={} activated={} actor={}", module.id(), activated, actorId);
        return true;
    }

    private static boolean inPlan(Map<String, ModuleState> entitled, String moduleId) {
        ModuleState state = entitled.get(moduleId);
        return state != null && state != ModuleState.DISABLED;
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

    /** Evicts the tenant's registry request cache now and again after commit. */
    private void evictAfterCommit(String schema) {
        registry.evict(schema);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registry.evict(schema);
                }
            });
        }
    }
}
