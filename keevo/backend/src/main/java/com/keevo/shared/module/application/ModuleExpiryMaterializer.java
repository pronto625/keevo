package com.keevo.shared.module.application;

import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.NewStatus;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Transition;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.application.port.ModuleExpiryStore;
import com.keevo.shared.module.application.port.ModuleExpiryStore.DueRow;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Story 16.1 (MOD-FR13) — nightly materialisation of module licence statuses. <b>Bookkeeping, never enforcement:</b>
 * every effective state is already computed from {@code expires_at} / {@code grace_until} at request time
 * ({@code ModuleStateRules}), so a missed run never lets a module be used past its end.
 *
 * <p>For each live tenant, in its own transaction (under the lifecycle lock {@code module-lifecycle:<tenant>}):
 * <ul>
 *   <li>{@code TRIAL} / {@code ACTIVE} rows with {@code expires_at <= now} → {@code EXPIRED} (dates kept);</li>
 *   <li>{@code EXPIRED} rows (or rows just expired) with {@code grace_until} null or {@code <= now} →
 *       {@code DISABLED}, {@code disabled_at = now} (dates kept).</li>
 * </ul>
 * Only {@code PLAN} / {@code ADMIN} rows of installed paid modules: {@code SYSTEM} rows, free and core modules are
 * never touched; no row, table or tenant data is ever deleted. The transitions written are published as one
 * {@link ModuleLicencesChangedEvent} per tenant, consumed after commit (owner notifications). Idempotent: a second
 * run finds nothing due. A tenant whose transaction fails is logged by code and skipped; the others proceed.
 */
@Service
public class ModuleExpiryMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ModuleExpiryMaterializer.class);

    private final ModuleCatalog catalog;
    private final PlanModuleRowStore tenants;
    private final ModuleExpiryStore store;
    private final TransactionLockPort locks;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ModuleExpiryMaterializer(ModuleCatalog catalog, PlanModuleRowStore tenants, ModuleExpiryStore store,
                                    TransactionLockPort locks, ApplicationEventPublisher events,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.catalog = catalog;
        this.tenants = tenants;
        this.store = store;
        this.locks = locks;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param tenants  tenants processed
     * @param expired  rows turned {@code EXPIRED}
     * @param disabled rows turned {@code DISABLED}
     * @param failures tenants skipped after a fault (by code)
     */
    public record Report(int tenants, int expired, int disabled, List<String> failures) {

        public Report {
            failures = List.copyOf(failures);
        }
    }

    /** One run over every live tenant, at the clock's current instant. */
    public Report materialize() {
        Instant now = clock.instant();
        int processed = 0;
        int expired = 0;
        int disabled = 0;
        List<String> failures = new ArrayList<>();
        for (LiveTenant tenant : tenants.liveTenants()) {
            if (!TenantSchema.isValid(tenant.tenantSchema())) {
                continue; // the platform tenant ("public") has no tenant modules
            }
            List<Transition> transitions;
            try {
                transitions = transactions.execute(status -> materialize(tenant, now));
            } catch (RuntimeException e) {
                failures.add(tenant.code());
                log.error("Module licences: tenant {} skipped ({}) — rows left unchanged", tenant.code(),
                        e.getClass().getSimpleName());
                continue;
            }
            processed++;
            if (transitions != null) {
                for (Transition t : transitions) {
                    if (t.status() == NewStatus.EXPIRED) {
                        expired++;
                    } else {
                        disabled++;
                    }
                }
            }
        }
        log.info("Module licences: {} tenant(s) processed, {} failed — {} row(s) expired, {} disabled", processed,
                failures.size(), expired, disabled);
        return new Report(processed, expired, disabled, failures);
    }

    /** One tenant, inside its transaction: writes the due transitions and publishes them. */
    private List<Transition> materialize(LiveTenant tenant, Instant now) {
        locks.lock(ModuleLifecycleService.LOCK_PREFIX + tenant.id()); // never interleave with a super-admin write
        List<Transition> transitions = new ArrayList<>();
        for (DueRow row : store.lockDueRows(tenant.id(), now)) {
            Optional<ModuleManifest> module = catalog.find(row.moduleId())
                    .filter(m -> !m.free() && !m.isCore());
            if (module.isEmpty()) {
                continue; // free / core / uninstalled module: never part of the licence lifecycle
            }
            boolean graceOver = row.graceUntil() == null || !row.graceUntil().isAfter(now);
            if (graceOver) {
                if (store.markDisabled(tenant.id(), row.moduleId(), now)) {
                    transitions.add(transition(module.get(), row, NewStatus.DISABLED));
                }
            } else if (store.markExpired(tenant.id(), row.moduleId())) {
                transitions.add(transition(module.get(), row, NewStatus.EXPIRED));
            }
        }
        if (!transitions.isEmpty()) {
            events.publishEvent(new ModuleLicencesChangedEvent(tenant.tenantSchema(), transitions, null, now));
            log.info("Module licences: tenant {} — {}", tenant.code(), transitions.stream()
                    .map(t -> t.moduleId() + "→" + t.status()).toList());
        }
        return transitions;
    }

    private static Transition transition(ModuleManifest module, DueRow row, NewStatus status) {
        return new Transition(module.id(), module.displayName(),
                ModuleLicencesChangedEvent.Origin.valueOf(row.origin().name()), status, row.graceUntil());
    }
}
