package com.keevo.migration.application;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.LegacyIdentityImportPort.RemovalPreparation;
import com.keevo.identity.api.TenantDeletionPort;
import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.Cutover;
import com.keevo.migration.domain.model.CutoverOwner;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.CutoverReport;
import com.keevo.migration.domain.model.CutoverStatus;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.in.BrefCutoverUseCase.Acceptance;
import com.keevo.migration.domain.port.in.BrefReconciliationUseCase;
import com.keevo.migration.domain.port.out.BrefOwnerDirectory;
import com.keevo.migration.domain.port.out.CutoverStore;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("24.9 — cutover command: start, per-owner isolation, gate, activation, rollback")
class BrefCutoverServiceTest {

    private static final Instant T = Instant.parse("2026-11-11T10:00:00Z");

    private final BrefOwnerImportService imports = mock(BrefOwnerImportService.class);
    private final BrefReconciliationUseCase reconciliation = mock(BrefReconciliationUseCase.class);
    private final BrefOwnerDirectory directory = mock(BrefOwnerDirectory.class);
    private final CutoverStore store = mock(CutoverStore.class);
    private final MigrationRecordStore records = mock(MigrationRecordStore.class);
    private final LegacyIdentityImportPort identity = mock(LegacyIdentityImportPort.class);
    private final TenantDeletionPort deletion = mock(TenantDeletionPort.class);
    private final com.keevo.messaging.api.NotificationImportPort notifications =
            mock(com.keevo.messaging.api.NotificationImportPort.class);
    private final MigrationUnitOfWork uow = mock(MigrationUnitOfWork.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
    private BrefCutoverService service;
    private final UUID cutoverId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new BrefCutoverService(imports, reconciliation, directory, store, records, identity, deletion,
                notifications, uow, meters, Clock.fixed(T, ZoneOffset.UTC));
        service.launcher = Runnable::run;
        when(uow.platform(any())).thenAnswer(i -> ((Supplier<Object>) i.getArgument(0)).get());
        when(directory.available()).thenReturn(true);
    }

    private Cutover cutover(CutoverStatus status) {
        return new Cutover(cutoverId, SourceKind.DATABASE, status, 2, 1, actor, T, null, T, null, null, null, null,
                null, List.of(), List.of(), CutoverReport.EMPTY);
    }

    private static CutoverOwner line(int position, CutoverOwnerStatus status, UUID tenant) {
        return new CutoverOwner(UUID.randomUUID(), position, CutoverOwner.label(position), null, status, null, tenant,
                null, null, null, null, null);
    }

    private static MigrationBatch batch(UUID owner, BatchStatus status) {
        return new MigrationBatch(UUID.randomUUID(), owner, SourceKind.DATABASE, status,
                status == BatchStatus.COMPLETED ? UUID.randomUUID() : null, null, T, T, Map.of(), List.of(),
                List.of(), null);
    }

    @Test
    void startValidatesSourceParallelismAndOpenCutover() {
        assertThatThrownBy(() -> service.start("EXPORT", null, actor)).isInstanceOf(DomainException.class)
                .hasMessageContaining(BrefCutoverService.SOURCE_ONLY_DATABASE);
        assertThatThrownBy(() -> service.start("DATABASE", 9, actor)).hasMessageContaining(BrefCutoverService.PARALLELISM_RANGE);
        when(store.create(any(), anyInt(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.start("DATABASE", 2, actor))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE))
                .hasMessageContaining(BrefCutoverService.OPEN);
    }

    @Test
    void oneOwnerFailingNeverStopsTheOthers() {
        UUID ok = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        when(store.create(any(), anyInt(), any())).thenReturn(Optional.of(cutoverId));
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.RUNNING)));
        when(directory.owners(isNull(), anyInt())).thenReturn(List.of(broken, ok));
        CutoverOwner first = new CutoverOwner(broken, 1, "Compte 1", null, CutoverOwnerStatus.PENDING, null, null,
                null, null, null, null, null);
        CutoverOwner second = new CutoverOwner(ok, 2, "Compte 2", null, CutoverOwnerStatus.PENDING, null, null, null,
                null, null, null, null);
        when(store.ownersToProcess(cutoverId, 0, BrefCutoverService.PAGE)).thenReturn(List.of(first, second));
        when(store.ownersToProcess(cutoverId, 2, BrefCutoverService.PAGE)).thenReturn(List.of());
        when(imports.importOwner(eq(broken), anyString(), any()))
                .thenThrow(new DomainException(ErrorCode.MIGRATION_EXPORT_INVALID, "row"));
        MigrationBatch done = batch(ok, BatchStatus.COMPLETED);
        when(imports.importOwner(eq(ok), anyString(), any())).thenReturn(done);
        ReconciliationReport report = ReconciliationReport.of(UUID.randomUUID(), done.id(), ok, done.tenantId(), T, T,
                List.of());
        when(reconciliation.reconcile(done.id(), actor)).thenReturn(report);
        when(store.aggregate(eq(cutoverId), any(), any(), anyInt())).thenReturn(CutoverReport.EMPTY);

        service.start("DATABASE", 2, actor);

        verify(store).ownerDone(eq(cutoverId), eq(broken), eq(CutoverOwnerStatus.FAILED), isNull(), isNull(),
                eq("Une ligne Bref de ce compte est illisible"), any(), isNull(), any());
        verify(store).ownerDone(eq(cutoverId), eq(ok), eq(CutoverOwnerStatus.RECONCILED), eq(done.id()),
                eq(done.tenantId()), isNull(), any(), any(), any());
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.READY), isNull(), any());
    }

    @Test
    void goIsRefusedWithTheBlockingOwners() {
        CutoverOwner clean = line(1, CutoverOwnerStatus.RECONCILED, UUID.randomUUID());
        CutoverOwner gap = line(2, CutoverOwnerStatus.GAP, UUID.randomUUID());
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        when(store.ownerLines(eq(cutoverId), anyCollection())).thenReturn(List.of(clean, gap));
        when(reconciliation.blockingOwners(anyCollection()))
                .thenReturn(Map.of(gap.brefOwnerId(), "Écart de réconciliation à corriger"));

        assertThatThrownBy(() -> service.activate(cutoverId, "ACTIVER", List.of(), actor))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException d = (DomainException) e;
                    assertThat(d.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> owners = (List<Map<String, Object>>) d.getDetails().get("blockingOwners");
                    assertThat(owners).singleElement().satisfies(o -> {
                        assertThat(o).containsEntry("label", "Compte 2");
                        assertThat(o).containsEntry("reason", "Écart de réconciliation à corriger");
                    });
                });
        verify(identity, never()).activateImportedTenants(anyCollection(), anyString());
        assertThatThrownBy(() -> service.activate(cutoverId, "oui", List.of(), actor))
                .hasMessageContaining(BrefCutoverService.GO_PHRASE);
        assertThatThrownBy(() -> service.activate(cutoverId, "ACTIVER",
                List.of(new Acceptance(gap.brefOwnerId(), "court")), actor))
                .hasMessageContaining(BrefCutoverService.REASON_REQUIRED);
    }

    @Test
    void acceptedGapIsActivatedWithTheCleanOwnersAndInvitationsRedated() {
        CutoverOwner clean = line(1, CutoverOwnerStatus.RECONCILED, UUID.randomUUID());
        CutoverOwner gap = line(2, CutoverOwnerStatus.GAP, UUID.randomUUID());
        CutoverOwner blocked = line(3, CutoverOwnerStatus.BLOCKED, null);
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        when(store.ownerLines(eq(cutoverId), anyCollection())).thenReturn(List.of(clean, gap, blocked));
        when(reconciliation.blockingOwners(anyCollection())).thenReturn(Map.of(gap.brefOwnerId(), "Écart",
                blocked.brefOwnerId(), "Dernier import non terminé"));
        UUID invitation = UUID.randomUUID();
        when(store.targets(anyCollection(), eq(List.of(MigrationRecord.INVITATIONS))))
                .thenReturn(Map.of(clean.brefOwnerId(), Map.of(MigrationRecord.INVITATIONS, List.of(invitation))));
        when(identity.activateImportedTenants(anyCollection(), eq(BrefMappings.SUSPENSION_REASON)))
                .thenReturn(Set.of(clean.tenantId(), gap.tenantId()));
        when(store.aggregate(eq(cutoverId), any(), any(), anyInt())).thenReturn(CutoverReport.EMPTY);
        when(store.transition(eq(cutoverId), anyCollection(), eq(CutoverStatus.ACTIVATED), any(), eq(false)))
                .thenReturn(true);

        service.activate(cutoverId, " ACTIVER ", List.of(
                new Acceptance(gap.brefOwnerId(), "Écart documenté par la répétition"),
                new Acceptance(blocked.brefOwnerId(), "Cas d'identité résolu après le go")), actor);

        verify(identity).activateImportedTenants(
                org.mockito.ArgumentMatchers.argThat(c -> c.size() == 2 && c.contains(clean.tenantId())
                        && c.contains(gap.tenantId())), eq(BrefMappings.SUSPENSION_REASON));
        verify(identity).redateImportedInvitations(List.of(invitation));
        verify(store).ownersStatus(eq(cutoverId), eq(List.of(clean.brefOwnerId(), gap.brefOwnerId())),
                eq(CutoverOwnerStatus.ACTIVATED), isNull());
        verify(store).activated(eq(cutoverId), eq(actor), anyList());
    }

    @Test
    void rollbackIsRefusedAfterTheGo() {
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.ACTIVATED)));
        assertThatThrownBy(() -> service.rollback(cutoverId, "RETOUR ARRIERE", actor))
                .hasMessageContaining(BrefCutoverService.AFTER_GO);
        assertThatThrownBy(() -> service.resume(cutoverId, actor)).hasMessageContaining(BrefCutoverService.AFTER_GO_RUN);
        assertThatThrownBy(() -> service.rollback(cutoverId, "oui", actor))
                .hasMessageContaining(BrefCutoverService.ROLLBACK_PHRASE);
    }

    @Test
    void rollbackRemovesTheCompanyAndOnlyTheCreatedAccounts() {
        UUID tenant = UUID.randomUUID();
        CutoverOwner owner = line(1, CutoverOwnerStatus.RECONCILED, tenant);
        UUID linked = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        UUID auth = UUID.randomUUID();
        UUID webPush = UUID.randomUUID();
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        when(store.transition(eq(cutoverId), anyCollection(), eq(CutoverStatus.ROLLING_BACK), any(), eq(false)))
                .thenReturn(true);
        when(store.owners(cutoverId, null, 0, BrefCutoverService.PAGE)).thenReturn(List.of(owner));
        when(store.owners(cutoverId, null, 1, BrefCutoverService.PAGE)).thenReturn(List.of());
        when(store.targets(anyCollection(), anyCollection())).thenReturn(Map.of(owner.brefOwnerId(), Map.of(
                MigrationRecord.TENANTS, List.of(tenant), MigrationRecord.LINKED_USERS, List.of(linked),
                MigrationRecord.USERS, List.of(created, linked), MigrationRecord.AUTHENTICATORS, List.of(auth),
                MigrationRecord.WEB_PUSH_SUBSCRIPTIONS, List.of(webPush))));
        when(identity.prepareImportedTenantRemoval(tenant, BrefMappings.SUSPENSION_REASON, List.of(linked),
                List.of(auth))).thenReturn(RemovalPreparation.READY);
        when(deletion.forgetImportedNow(tenant)).thenReturn(true);
        when(store.aggregate(eq(cutoverId), any(), any(), anyInt())).thenReturn(CutoverReport.EMPTY);

        service.rollback(cutoverId, "RETOUR ARRIERE", actor);

        verify(deletion).forgetImportedNow(tenant);
        verify(identity).deleteOrphanImportedAccounts(Set.of(created));
        verify(notifications).deleteImportedWebPushSubscriptions(List.of(webPush));
        verify(store).purgeOwners(List.of(owner.brefOwnerId()));
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.ROLLED_BACK), isNull(), any());
    }

    @Test
    void rollbackKeepsAnActiveCompanyAndReportsIt() {
        UUID tenant = UUID.randomUUID();
        CutoverOwner owner = line(1, CutoverOwnerStatus.RECONCILED, tenant);
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        when(store.transition(eq(cutoverId), anyCollection(), eq(CutoverStatus.ROLLING_BACK), any(), eq(false)))
                .thenReturn(true);
        when(store.owners(cutoverId, null, 0, BrefCutoverService.PAGE)).thenReturn(List.of(owner));
        when(store.owners(cutoverId, null, 1, BrefCutoverService.PAGE)).thenReturn(List.of());
        when(store.targets(anyCollection(), anyCollection())).thenReturn(Map.of(owner.brefOwnerId(),
                Map.of(MigrationRecord.TENANTS, List.of(tenant))));
        when(identity.prepareImportedTenantRemoval(eq(tenant), anyString(), anyCollection(), anyCollection()))
                .thenReturn(RemovalPreparation.REFUSED);
        when(store.aggregate(eq(cutoverId), any(), any(), anyInt())).thenReturn(CutoverReport.EMPTY);

        service.rollback(cutoverId, "RETOUR ARRIERE", actor);

        verify(deletion, never()).forgetImportedNow(any());
        verify(store, never()).purgeOwners(anyCollection());
        verify(store).ownersStatus(cutoverId, List.of(owner.brefOwnerId()), CutoverOwnerStatus.FAILED,
                BrefCutoverService.ROLLBACK_REFUSED);
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.FAILED), eq("Retour arrière incomplet : 1 compte(s) à "
                + "reprendre, relancez-le"), any());
    }

    @Test
    void aFailedLineBlocksTheGoEvenWhenAnOlderBatchIsReconciled() {
        CutoverOwner failed = line(1, CutoverOwnerStatus.FAILED, UUID.randomUUID());
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        when(store.ownerLines(eq(cutoverId), anyCollection())).thenReturn(List.of(failed));
        when(reconciliation.blockingOwners(anyCollection())).thenReturn(Map.of());
        assertThatThrownBy(() -> service.activate(cutoverId, "ACTIVER", List.of(), actor))
                .hasMessageContaining("1 compte(s) bloquent");
        verify(store, never()).transition(any(), anyCollection(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void theGoLosesAgainstARollbackStartedMeanwhile() {
        CutoverOwner clean = line(1, CutoverOwnerStatus.RECONCILED, UUID.randomUUID());
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)),
                Optional.of(cutover(CutoverStatus.ROLLING_BACK)));
        when(store.ownerLines(eq(cutoverId), anyCollection())).thenReturn(List.of(clean));
        when(reconciliation.blockingOwners(anyCollection())).thenReturn(Map.of());
        assertThatThrownBy(() -> service.activate(cutoverId, "ACTIVER", List.of(), actor))
                .hasMessageContaining(BrefCutoverService.RUNNING);
        verify(identity, never()).activateImportedTenants(anyCollection(), anyString());
    }

    @Test
    void resumeRelaunchesTheRunAndIsRefusedOnceARollbackStarted() {
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.FAILED)));
        when(store.transition(eq(cutoverId), anyCollection(), eq(CutoverStatus.RUNNING), any(), eq(true)))
                .thenReturn(true);
        List<Runnable> launched = new java.util.ArrayList<>();
        service.launcher = launched::add;
        service.resume(cutoverId, actor);
        assertThat(launched).hasSize(1);

        Cutover partlyRolledBack = new Cutover(cutoverId, SourceKind.DATABASE, CutoverStatus.FAILED, 2, 1, actor, T,
                null, T, null, null, null, null, null, List.of(new CutoverReport.StatusCount("ROLLED_BACK", "x", 1)),
                List.of(), CutoverReport.EMPTY);
        when(store.find(cutoverId)).thenReturn(Optional.of(partlyRolledBack));
        assertThatThrownBy(() -> service.resume(cutoverId, actor))
                .hasMessageContaining(BrefCutoverService.ROLLBACK_STARTED);
    }

    @Test
    void theListingFollowsTheKeysetPastAFullPage() {
        List<UUID> first = java.util.stream.IntStream.range(0, BrefCutoverService.PAGE).mapToObj(i -> UUID.randomUUID())
                .toList();
        when(store.create(any(), anyInt(), any())).thenReturn(Optional.of(cutoverId));
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.RUNNING)));
        when(directory.owners(isNull(), anyInt())).thenReturn(first);
        when(directory.owners(eq(first.get(first.size() - 1)), anyInt())).thenReturn(List.of(UUID.randomUUID()));
        when(store.ownersToProcess(eq(cutoverId), anyInt(), anyInt())).thenReturn(List.of());
        when(store.aggregate(eq(cutoverId), any(), any(), anyInt())).thenReturn(CutoverReport.EMPTY);
        service.start("DATABASE", 1, actor);
        verify(directory).owners(first.get(first.size() - 1), BrefCutoverService.PAGE);
        verify(store, org.mockito.Mockito.times(2)).addOwners(eq(cutoverId), anyList());
    }

    @Test
    void aCompanyNoLongerPendingDeletionKeepsItsRecords() {
        UUID tenant = UUID.randomUUID();
        CutoverOwner owner = line(1, CutoverOwnerStatus.RECONCILED, tenant);
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        when(store.transition(eq(cutoverId), anyCollection(), eq(CutoverStatus.ROLLING_BACK), any(), eq(false)))
                .thenReturn(true);
        when(store.owners(cutoverId, null, 0, BrefCutoverService.PAGE)).thenReturn(List.of(owner));
        when(store.owners(cutoverId, null, 1, BrefCutoverService.PAGE)).thenReturn(List.of());
        when(store.targets(anyCollection(), anyCollection())).thenReturn(Map.of(owner.brefOwnerId(),
                Map.of(MigrationRecord.TENANTS, List.of(tenant))));
        when(identity.prepareImportedTenantRemoval(eq(tenant), anyString(), anyCollection(), anyCollection()))
                .thenReturn(RemovalPreparation.READY);
        when(deletion.forgetImportedNow(tenant)).thenReturn(false);
        when(store.aggregate(eq(cutoverId), any(), any(), anyInt())).thenReturn(CutoverReport.EMPTY);
        service.rollback(cutoverId, "RETOUR ARRIERE", actor);
        verify(store, never()).purgeOwners(anyCollection());
        verify(store).ownersStatus(cutoverId, List.of(owner.brefOwnerId()), CutoverOwnerStatus.FAILED,
                BrefCutoverService.ROLLBACK_OWNER_FAILED);
    }

    // ── 24.13: one owner's rollback before the « go » ─────────────────────────────────────────────────────────────

    private void ownerRollbackReady(CutoverOwner line) {
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        when(store.owner(cutoverId, line.brefOwnerId())).thenReturn(Optional.of(line));
        when(store.transition(eq(cutoverId), anyCollection(), eq(CutoverStatus.RUNNING), any(), eq(false)))
                .thenReturn(true);
        when(store.aggregate(eq(cutoverId), any(), any(), anyInt())).thenReturn(CutoverReport.EMPTY);
    }

    @Test
    @DisplayName("24.13 — one owner in gap: its company removed, its line back to pending, the cutover to relaunch")
    void ownerRollbackRemovesOneCompanyAndPutsTheLineBackToPending() {
        UUID tenant = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        UUID linked = UUID.randomUUID();
        CutoverOwner owner = line(3, CutoverOwnerStatus.GAP, tenant);
        ownerRollbackReady(owner);
        when(store.targets(List.of(owner.brefOwnerId()), BrefCutoverService.ROLLBACK_TABLES)).thenReturn(Map.of(
                owner.brefOwnerId(), Map.of(MigrationRecord.TENANTS, List.of(tenant), MigrationRecord.USERS,
                        List.of(created, linked), MigrationRecord.LINKED_USERS, List.of(linked))));
        when(identity.prepareImportedTenantRemoval(eq(tenant), eq(BrefMappings.SUSPENSION_REASON), anyCollection(),
                anyCollection())).thenReturn(RemovalPreparation.READY);
        when(deletion.forgetImportedNow(tenant)).thenReturn(true);

        service.rollbackOwner(cutoverId, owner.brefOwnerId(), " RETOUR ARRIERE ", actor);

        verify(store).transition(eq(cutoverId), eq(java.util.EnumSet.of(CutoverStatus.READY, CutoverStatus.FAILED)),
                eq(CutoverStatus.RUNNING), any(), eq(false));
        verify(deletion).forgetImportedNow(tenant);
        verify(identity).deleteOrphanImportedAccounts(Set.of(created));
        verify(store).purgeOwners(List.of(owner.brefOwnerId()));
        verify(store).resetOwner(cutoverId, owner.brefOwnerId());
        verify(store, never()).ownersStatus(any(), anyCollection(), eq(CutoverOwnerStatus.ROLLED_BACK), any());
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.FAILED), eq(BrefCutoverService.OWNER_RESET), any());
    }

    @Test
    @DisplayName("24.13 — a blocked owner (nothing written) is only purged and put back to pending")
    void ownerRollbackOfABlockedOwnerOnlyResetsIt() {
        CutoverOwner owner = line(1, CutoverOwnerStatus.BLOCKED, null);
        ownerRollbackReady(owner);
        when(store.targets(anyCollection(), anyCollection())).thenReturn(Map.of());
        service.rollbackOwner(cutoverId, owner.brefOwnerId(), "RETOUR ARRIERE", actor);
        verify(deletion, never()).forgetImportedNow(any());
        verify(store).resetOwner(cutoverId, owner.brefOwnerId());
    }

    @Test
    @DisplayName("24.13 — wrong phrase, unknown owner, nothing to remove, global rollback started")
    void ownerRollbackValidations() {
        UUID unknown = UUID.randomUUID();
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.READY)));
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, unknown, "oui", actor))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining(BrefCutoverService.ROLLBACK_PHRASE);
        when(store.owner(cutoverId, unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, unknown, "RETOUR ARRIERE", actor))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining(BrefCutoverService.OWNER_NOT_IN_CUTOVER);
        for (CutoverOwnerStatus st : List.of(CutoverOwnerStatus.PENDING, CutoverOwnerStatus.NOT_MIGRATED,
                CutoverOwnerStatus.ACTIVATED, CutoverOwnerStatus.ROLLED_BACK)) {
            CutoverOwner l = line(1, st, null);
            when(store.owner(cutoverId, l.brefOwnerId())).thenReturn(Optional.of(l));
            assertThatThrownBy(() -> service.rollbackOwner(cutoverId, l.brefOwnerId(), "RETOUR ARRIERE", actor))
                    .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE))
                    .hasMessageContaining(BrefCutoverService.OWNER_NOTHING_TO_REMOVE);
        }
        Cutover partlyRolledBack = new Cutover(cutoverId, SourceKind.DATABASE, CutoverStatus.FAILED, 2, 1, actor, T,
                null, T, null, null, null, null, null, List.of(new CutoverReport.StatusCount("ROLLED_BACK", "x", 1)),
                List.of(), CutoverReport.EMPTY);
        when(store.find(cutoverId)).thenReturn(Optional.of(partlyRolledBack));
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, unknown, "RETOUR ARRIERE", actor))
                .hasMessageContaining(BrefCutoverService.ROLLBACK_STARTED);
        verify(store, never()).transition(any(), anyCollection(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("24.13 — refused while running, rolling back, after the « go » or once rolled back: nothing removed")
    void ownerRollbackRefusedWhenTheCutoverIsBusyOrDecided() {
        UUID owner = UUID.randomUUID();
        for (CutoverStatus st : List.of(CutoverStatus.RUNNING, CutoverStatus.ROLLING_BACK, CutoverStatus.ACTIVATED,
                CutoverStatus.ROLLED_BACK)) {
            when(store.find(cutoverId)).thenReturn(Optional.of(cutover(st)));
            when(store.transition(eq(cutoverId), anyCollection(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);
            assertThatThrownBy(() -> service.rollbackOwner(cutoverId, owner, "RETOUR ARRIERE", actor))
                    .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE))
                    .hasMessageContaining(st == CutoverStatus.ACTIVATED ? BrefCutoverService.AFTER_GO
                            : BrefCutoverService.refusal(st));
        }
        verify(store, never()).purgeOwners(anyCollection());
        verify(store, never()).finish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("24.13 — an active company is never removed: line failed with the reason, error returned")
    void ownerRollbackKeepsAnActiveCompany() {
        UUID tenant = UUID.randomUUID();
        CutoverOwner owner = line(1, CutoverOwnerStatus.RECONCILED, tenant);
        ownerRollbackReady(owner);
        when(store.targets(anyCollection(), anyCollection())).thenReturn(Map.of(owner.brefOwnerId(),
                Map.of(MigrationRecord.TENANTS, List.of(tenant))));
        when(identity.prepareImportedTenantRemoval(eq(tenant), anyString(), anyCollection(), anyCollection()))
                .thenReturn(RemovalPreparation.REFUSED);
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, owner.brefOwnerId(), "RETOUR ARRIERE", actor))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE))
                .hasMessageContaining(BrefCutoverService.ROLLBACK_REFUSED);
        verify(deletion, never()).forgetImportedNow(any());
        verify(store, never()).purgeOwners(anyCollection());
        verify(store, never()).resetOwner(any(), any());
        verify(store).ownersStatus(cutoverId, List.of(owner.brefOwnerId()), CutoverOwnerStatus.FAILED,
                BrefCutoverService.ROLLBACK_REFUSED);
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.FAILED), eq(BrefCutoverService.OWNER_RESET_FAILED),
                any());
    }

    @Test
    @DisplayName("24.13 review — a store failure under the lock: line failed, cutover released (FAILED), error returned")
    void ownerRollbackReleasesTheLockWhenAStoreCallThrows() {
        CutoverOwner owner = line(2, CutoverOwnerStatus.GAP, UUID.randomUUID());
        ownerRollbackReady(owner);
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.FAILED)));
        when(store.targets(anyCollection(), anyCollection())).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, owner.brefOwnerId(), "RETOUR ARRIERE", actor))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE))
                .hasMessageContaining(BrefCutoverService.ROLLBACK_OWNER_FAILED);
        verify(store).ownersStatus(cutoverId, List.of(owner.brefOwnerId()), CutoverOwnerStatus.FAILED,
                BrefCutoverService.ROLLBACK_OWNER_FAILED);
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.FAILED), eq(BrefCutoverService.OWNER_RESET_FAILED),
                any());
        verify(store, never()).resetOwner(any(), any());
    }

    @Test
    @DisplayName("24.13 review — a refusal found under the lock restores the previous status and label, same error code")
    void ownerRollbackRefusedUnderTheLockRestoresThePreviousState() {
        CutoverOwner gap = line(2, CutoverOwnerStatus.GAP, UUID.randomUUID());
        CutoverOwner nowPending = new CutoverOwner(gap.brefOwnerId(), 2, gap.label(), null, CutoverOwnerStatus.PENDING,
                null, null, null, null, null, null, null);
        ownerRollbackReady(gap);
        when(store.owner(cutoverId, gap.brefOwnerId())).thenReturn(Optional.of(gap), Optional.of(nowPending));
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, gap.brefOwnerId(), "RETOUR ARRIERE", actor))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE))
                .hasMessageContaining(BrefCutoverService.OWNER_NOTHING_TO_REMOVE);
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.READY), isNull(), any());
        verify(store, never()).purgeOwners(anyCollection());

        CutoverOwner gone = line(3, CutoverOwnerStatus.GAP, UUID.randomUUID());
        when(store.owner(cutoverId, gone.brefOwnerId())).thenReturn(Optional.of(gone), Optional.empty());
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, gone.brefOwnerId(), "RETOUR ARRIERE", actor))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining(BrefCutoverService.OWNER_NOT_IN_CUTOVER);
    }

    @Test
    @DisplayName("24.13 review — a global rollback started between the check and the lock: refused, nothing reset")
    void ownerRollbackRefusedWhenAGlobalRollbackStartedMeanwhile() {
        CutoverOwner owner = line(1, CutoverOwnerStatus.FAILED, UUID.randomUUID());
        ownerRollbackReady(owner);
        Cutover partlyRolledBack = new Cutover(cutoverId, SourceKind.DATABASE, CutoverStatus.FAILED, 2, 1, actor, T,
                null, T, null, null, null, null, "Retour arrière incomplet : 1 compte(s) à reprendre, relancez-le",
                List.of(new CutoverReport.StatusCount("ROLLED_BACK", "x", 1)), List.of(), CutoverReport.EMPTY);
        when(store.find(cutoverId)).thenReturn(Optional.of(cutover(CutoverStatus.FAILED)),
                Optional.of(partlyRolledBack));
        assertThatThrownBy(() -> service.rollbackOwner(cutoverId, owner.brefOwnerId(), "RETOUR ARRIERE", actor))
                .hasMessageContaining(BrefCutoverService.ROLLBACK_STARTED);
        verify(store, never()).resetOwner(any(), any());
        verify(store).finish(eq(cutoverId), eq(CutoverStatus.FAILED), isNull(), any());
    }
}
