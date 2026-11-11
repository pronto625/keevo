package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.OwnerFigures;
import com.keevo.migration.domain.model.ReconciliationCheck;
import com.keevo.migration.domain.model.ReconciliationLine;
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("24.9 — cutover rules: owner outcome, figures, error labels")
class CutoverFiguresTest {

    private static final Instant T = Instant.parse("2026-11-11T10:00:00Z");

    private static MigrationBatch batch(BatchStatus status, List<BlockedCase> cases, String error) {
        return new MigrationBatch(UUID.randomUUID(), UUID.randomUUID(), SourceKind.DATABASE, status,
                status == BatchStatus.COMPLETED ? UUID.randomUUID() : null, null, T, T, Map.of(), cases,
                List.of(new ImportNote("PRODUCT_PRICE_ROUNDED", "Prix arrondi", 3),
                        new ImportNote("X", "Prix arrondi", 2)), error);
    }

    private static ReconciliationLine line(String label, String expected, String actual,
                                           ReconciliationLine.Status status) {
        return ReconciliationLine.of(label, expected, actual, null, null, status, null);
    }

    private static ReconciliationReport report(ReconciliationLine.Status worst) {
        List<ReconciliationCheck> checks = List.of(
                ReconciliationCheck.of(ReconciliationCheck.Kind.COUNTS, List.of(
                        line("Boutiques", "2", "2", ReconciliationLine.Status.OK),
                        line("Produits", "10", "9", worst))),
                ReconciliationCheck.of(ReconciliationCheck.Kind.SALES_BY_STORE_MONTH, List.of(
                        line("Boutique 1 — mars 2026", "1000.5 F", "1001 F", ReconciliationLine.Status.EXPLAINED),
                        line("Boutique 1 — avril 2026", "2000 F", "2000 F", ReconciliationLine.Status.OK))),
                ReconciliationCheck.of(ReconciliationCheck.Kind.COLLECTED, List.of(
                        line("Total encaissé", "500 F", "500 F", ReconciliationLine.Status.OK))),
                ReconciliationCheck.of(ReconciliationCheck.Kind.SUBSCRIPTION, List.of(
                        line("Plan", "Pro", "Pro", ReconciliationLine.Status.OK),
                        line("Crédit du compte", "750 F", "750 F", ReconciliationLine.Status.OK))),
                ReconciliationCheck.of(ReconciliationCheck.Kind.SAMPLE, List.of(
                        line("Facture BRF-1", "Présent", "Absent", ReconciliationLine.Status.OK))));
        return ReconciliationReport.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), T,
                T, checks);
    }

    @Test
    void reconciledAndGap() {
        assertThat(CutoverFigures.outcome(batch(BatchStatus.COMPLETED, List.of(), null),
                report(ReconciliationLine.Status.OK)).status()).isEqualTo(CutoverOwnerStatus.RECONCILED);
        CutoverFigures.Outcome gap = CutoverFigures.outcome(batch(BatchStatus.COMPLETED, List.of(), null),
                report(ReconciliationLine.Status.GAP));
        assertThat(gap.status()).isEqualTo(CutoverOwnerStatus.GAP);
        assertThat(gap.reason()).startsWith("1 écart(s) hors tolérance : Nombre de lignes par table");
    }

    @Test
    void completedWithoutReportIsFailed() {
        assertThat(CutoverFigures.outcome(batch(BatchStatus.COMPLETED, List.of(), null), null).status())
                .isEqualTo(CutoverOwnerStatus.FAILED);
    }

    @Test
    void blockedOnlyByNothingToMigrateIsNotMigrated() {
        UUID id = UUID.randomUUID();
        CutoverFigures.Outcome none = CutoverFigures.outcome(batch(BatchStatus.BLOCKED, List.of(
                BlockedCase.of(BlockedCase.Kind.OWNER_NO_SHOP, "user", id)), null), null);
        assertThat(none.status()).isEqualTo(CutoverOwnerStatus.NOT_MIGRATED);
        assertThat(none.reason()).isEqualTo("Aucune boutique à importer");
        CutoverFigures.Outcome blocked = CutoverFigures.outcome(batch(BatchStatus.BLOCKED, List.of(
                BlockedCase.of(BlockedCase.Kind.OWNER_INACTIVE, "user", id),
                BlockedCase.of(BlockedCase.Kind.IDENTITY_AMBIGUOUS, "détail qui reste hors du motif", "user", id)),
                null), null);
        assertThat(blocked.status()).isEqualTo(CutoverOwnerStatus.BLOCKED);
        assertThat(blocked.reason()).isEqualTo("Compte désactivé dans Bref ; "
                + "Rapprochement impossible : plusieurs comptes Keevo correspondent");
    }

    @Test
    void failedImportKeepsItsLabel() {
        assertThat(CutoverFigures.outcome(batch(BatchStatus.FAILED, List.of(), "Arrêt à l'étape « boutiques »"),
                null)).isEqualTo(new CutoverFigures.Outcome(CutoverOwnerStatus.FAILED,
                "Arrêt à l'étape « boutiques »"));
    }

    @Test
    void figuresSumTheMoneyLinesAndCounts() {
        OwnerFigures f = CutoverFigures.figures(report(ReconciliationLine.Status.OK));
        assertThat(f.counts()).containsEntry("Produits", new OwnerFigures.Pair(BigDecimal.TEN, new BigDecimal("9")));
        assertThat(f.sums().get(CutoverFigures.SALES)).isEqualTo(new OwnerFigures.Pair(new BigDecimal("3000.5"),
                new BigDecimal("3001")));
        assertThat(f.sums().get(CutoverFigures.COLLECTED).actual()).isEqualByComparingTo("500");
        assertThat(f.sums().get(CutoverFigures.CREDIT).expected()).isEqualByComparingTo("750");
        assertThat(f.sums()).doesNotContainKey("Plan");
        assertThat(CutoverFigures.figures(null)).isEqualTo(OwnerFigures.NONE);
    }

    @Test
    void anomaliesByLabel() {
        assertThat(CutoverFigures.anomalies(batch(BatchStatus.COMPLETED, List.of(), null)))
                .containsExactly(Map.entry("Prix arrondi", 5L));
    }

    @Test
    void failureLabelsNeverQuoteTheException() {
        assertThat(CutoverFigures.failure(new DomainException(ErrorCode.MIGRATION_OWNER_NOT_FOUND, "secret")))
                .doesNotContain("secret").contains("Compte absent");
        assertThat(CutoverFigures.failure(new IllegalStateException("secret"))).isEqualTo(CutoverFigures.UNEXPECTED);
        assertThat(CutoverFigures.reason("x".repeat(400))).hasSize(CutoverFigures.REASON_MAX);
    }
}
