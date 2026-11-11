package com.keevo.migration.application;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantView;
import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.ReconciliationCheck;
import com.keevo.migration.domain.model.ReconciliationFigures.Actual;
import com.keevo.migration.domain.model.ReconciliationFigures.ActualSubscription;
import com.keevo.migration.domain.model.ReconciliationFigures.Expected;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedLink;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordsSummary;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleDocument;
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.in.BrefReconciliationUseCase;
import com.keevo.migration.domain.port.out.BrefMonetisationSourcePort;
import com.keevo.migration.domain.port.out.BrefSourcePort;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.port.out.ReconciliationStore;
import com.keevo.migration.domain.port.out.ReconciliationTargetReader;
import com.keevo.migration.domain.service.BrefReconciler;
import com.keevo.migration.domain.service.BrefReconciliationExpectations;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.subscription.api.PlanAdministrationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.8 — the reconciliation of an imported Bref owner (analysis §10.3, FUS-MIG3): reads the owner again from the
 * batch's source (the import's own read-only snapshot and monetisation section), computes what Keevo must hold
 * ({@link BrefReconciliationExpectations}), reads what Keevo holds ({@link ReconciliationTargetReader}, one read
 * transaction, plus the plan through the subscription module's port), compares ({@link BrefReconciler}) and stores the
 * report. A reconciliation that cannot run is stored {@code RECONCILIATION_FAILED} with a French label: the owner
 * stays blocked, the caller is never interrupted.
 *
 * <p>Cost per run: the source reads of one owner (as the import), 2 records queries, ≈ 10 aggregate target queries,
 * 1 plan read, 1 insert — never a query per row; O(n) memory (the owner's snapshot). No personal data in logs.
 */
@Service
public class BrefReconciliationService implements BrefReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(BrefReconciliationService.class);

    /** The record target of the plan decision ({@code MonetisationStep.TENANT_PLAN}, 24.5). */
    static final String TENANT_PLAN = "tenant_plan";
    static final String NOT_COMPLETED = "Seul un import terminé peut être réconcilié";
    static final String SOURCE_UNAVAILABLE = "La source Bref n'est pas accessible : relancez la réconciliation quand "
            + "elle l'est";
    static final String NOT_RUN = "La réconciliation n'a pas pu s'exécuter : relancez-la";

    private final Map<SourceKind, BrefSourcePort> sources = new EnumMap<>(SourceKind.class);
    private final Map<SourceKind, BrefMonetisationSourcePort> monetisation = new EnumMap<>(SourceKind.class);
    private final MigrationRecordStore batches;
    private final ReconciliationTargetReader target;
    private final ReconciliationStore reconciliations;
    private final TenantQueryPort tenants;
    private final PlanAdministrationPort plans;
    private final PlanCatalog planCatalog;
    private final MigrationUnitOfWork uow;
    private final Clock clock;

    public BrefReconciliationService(List<BrefSourcePort> sources, List<BrefMonetisationSourcePort> monetisation,
                                     MigrationRecordStore batches, ReconciliationTargetReader target,
                                     ReconciliationStore reconciliations, TenantQueryPort tenants,
                                     PlanAdministrationPort plans, PlanCatalog planCatalog, MigrationUnitOfWork uow,
                                     Clock clock) {
        sources.forEach(s -> this.sources.put(s.kind(), s));
        monetisation.forEach(s -> this.monetisation.put(s.kind(), s));
        this.batches = batches;
        this.target = target;
        this.reconciliations = reconciliations;
        this.tenants = tenants;
        this.plans = plans;
        this.planCatalog = planCatalog;
        this.uow = uow;
        this.clock = clock;
    }

    @Override
    public ReconciliationReport reconcile(UUID batchId, UUID actorId) {
        MigrationBatch batch = batch(batchId);
        if (batch.status() != BatchStatus.COMPLETED || batch.tenantId() == null) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, NOT_COMPLETED);
        }
        Instant started = clock.instant();
        ReconciliationReport report;
        try {
            List<ReconciliationCheck> checks = checks(batch);
            report = ReconciliationReport.of(UUID.randomUUID(), batchId, batch.brefOwnerId(), batch.tenantId(),
                    started, clock.instant(), checks);
        } catch (RuntimeException e) {
            boolean source = e instanceof DomainException d && d.getErrorCode() == ErrorCode.MIGRATION_SOURCE_UNAVAILABLE;
            // the message may quote source values (SQL detail): only the class is logged
            log.warn("[ADMIN AUDIT] Bref reconciliation could not run: batch={} owner={} error={}", batchId,
                    batch.brefOwnerId(), e.getClass().getSimpleName());
            report = ReconciliationReport.failed(UUID.randomUUID(), batchId, batch.brefOwnerId(), batch.tenantId(),
                    started, clock.instant(), source ? SOURCE_UNAVAILABLE : NOT_RUN);
        }
        reconciliations.save(report, actorId);
        log.info("[ADMIN AUDIT] Bref reconciliation: actor={} batch={} owner={} tenant={} status={} gaps={} "
                        + "explained={}", actorId, batchId, batch.brefOwnerId(), batch.tenantId(), report.status(),
                report.gaps(), report.explained());
        return report;
    }

    private List<ReconciliationCheck> checks(MigrationBatch batch) {
        UUID owner = batch.brefOwnerId();
        String schema = tenants.findById(batch.tenantId()).map(TenantView::schemaName)
                .orElseThrow(() -> new IllegalStateException("Imported tenant not found"));
        BrefSourcePort source = sources.get(batch.source());
        BrefMonetisationSourcePort section = monetisation.get(batch.source());
        if (source == null || !source.available() || section == null) {
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref source unavailable");
        }
        BrefSnapshot snapshot = source.load(owner);
        Set<UUID> accounts = new LinkedHashSet<>();
        accounts.add(owner);
        snapshot.members().stream().map(BrefAccount::id).forEach(accounts::add);
        BrefMonetisation m = section.load(owner, accounts);

        RecordsSummary records = target.records(owner);
        Instant planInstant = records.get(MigrationRecord.BREF_USER, TENANT_PLAN).firstAt();
        String freePlan = planCatalog.settings().freePlan().code();
        Expected expected = BrefReconciliationExpectations.expected(snapshot, m, records, planInstant, freePlan,
                batch.id());
        List<UUID> sampleIds = expected.sample().stream().map(SampleDocument::id).toList();
        List<UUID> tokens = expected.links().stream().map(ExpectedLink::token).toList();
        Actual actual = uow.platform(() -> {
            Actual read = target.read(schema, batch.tenantId(), sampleIds, tokens);
            CurrentPlan plan = plans.currentPlan(schema).orElse(null);
            return new Actual(read.counts(), read.sales(), read.collected(), read.expenses(), read.outstanding(),
                    read.sample(), read.links(), new ActualSubscription(plan == null ? null : plan.planType().code(),
                    plan == null ? null : plan.expiresAt(), read.subscription().creditXaf()));
        });
        return BrefReconciler.reconcile(expected, actual, notedByTable(records), this::planLabel);
    }

    private static Map<String, Long> notedByTable(RecordsSummary records) {
        Map<String, Long> noted = new HashMap<>();
        records.pairs().forEach((pair, count) -> {
            if (count.noted() > 0) {
                noted.merge(pair.substring(pair.indexOf('|') + 1), count.noted(), Long::sum);
            }
        });
        return noted;
    }

    private String planLabel(String code) {
        return code == null ? "aucun" : planCatalog.find(code).map(PlanDefinition::label).orElse("Plan inconnu");
    }

    @Override
    public ReconciliationReport report(UUID batchId) {
        batch(batchId);
        return reconciliations.latestForBatch(batchId).orElseThrow(this::notFound);
    }

    @Override
    public ReconciliationReport latestForOwner(UUID brefOwnerId) {
        return reconciliations.latestForOwner(brefOwnerId).orElseThrow(this::notFound);
    }

    @Override
    public Map<UUID, String> blockingOwners(Collection<UUID> brefOwnerIds) {
        return reconciliations.blockingOwners(brefOwnerIds);
    }

    private MigrationBatch batch(UUID batchId) {
        return batches.findBatch(batchId).orElseThrow(() -> new DomainException(ErrorCode.MIGRATION_BATCH_NOT_FOUND,
                "Migration batch not found"));
    }

    private DomainException notFound() {
        return new DomainException(ErrorCode.MIGRATION_RECONCILIATION_NOT_FOUND, "No reconciliation yet");
    }
}
