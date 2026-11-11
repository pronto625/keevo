package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.OwnerFigures;
import com.keevo.migration.domain.model.OwnerFigures.Pair;
import com.keevo.migration.domain.model.ReconciliationCheck;
import com.keevo.migration.domain.model.ReconciliationLine;
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.ReconciliationStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Story 24.9 — pure rules of the cutover: the outcome of one owner (import batch + reconciliation report → status and
 * French reason), the figures kept per owner for the report (counts and sums read from the reconciliation lines), the
 * French label of an error. No I/O.
 */
public final class CutoverFigures {

    /** Blocking cases meaning « nothing to migrate »: reported as NOT_MIGRATED, never blocking the « go » (D1). */
    static final Set<BlockedCase.Kind> NOTHING_TO_MIGRATE = EnumSet.of(BlockedCase.Kind.OWNER_DELETED,
            BlockedCase.Kind.OWNER_INACTIVE, BlockedCase.Kind.OWNER_NO_SHOP);

    public static final String SALES = "Ventes non annulées";
    public static final String COLLECTED = "Montant encaissé";
    public static final String EXPENSES = "Dépenses";
    public static final String OUTSTANDING = "Restant dû des ventes en cours";
    public static final String CREDIT = "Crédit du compte";

    static final String NOT_RECONCILED = "Import terminé mais réconciliation non enregistrée : relancez la bascule";
    static final String UNEXPECTED = "Erreur inattendue pendant l'import : relancez la bascule";
    static final int REASON_MAX = 300;

    private CutoverFigures() {
    }

    /** The status and French reason of one processed owner. */
    public record Outcome(CutoverOwnerStatus status, String reason) {
    }

    /**
     * The owner's outcome from its import batch and the reconciliation of that batch ({@code null} when none was
     * stored).
     */
    public static Outcome outcome(MigrationBatch batch, ReconciliationReport report) {
        if (batch.status() == BatchStatus.BLOCKED) {
            Set<String> labels = new LinkedHashSet<>();
            boolean nothing = !batch.cases().isEmpty();
            for (BlockedCase c : batch.cases()) {
                BlockedCase.Kind kind = kind(c.code());
                nothing &= kind != null && NOTHING_TO_MIGRATE.contains(kind);
                labels.add(kind == null ? c.label() : kind.label());
            }
            return new Outcome(nothing ? CutoverOwnerStatus.NOT_MIGRATED : CutoverOwnerStatus.BLOCKED,
                    reason(String.join(" ; ", labels)));
        }
        if (batch.status() != BatchStatus.COMPLETED) {
            return new Outcome(CutoverOwnerStatus.FAILED, reason(batch.errorLabel() == null
                    ? UNEXPECTED : batch.errorLabel()));
        }
        if (report == null) {
            return new Outcome(CutoverOwnerStatus.FAILED, NOT_RECONCILED);
        }
        if (report.status() == ReconciliationStatus.RECONCILED) {
            return new Outcome(CutoverOwnerStatus.RECONCILED, null);
        }
        if (report.errorLabel() != null) {
            return new Outcome(CutoverOwnerStatus.GAP, reason(report.errorLabel()));
        }
        Set<String> checks = new LinkedHashSet<>();
        report.checks().stream().filter(c -> c.status() == ReconciliationLine.Status.GAP)
                .forEach(c -> checks.add(c.label()));
        return new Outcome(CutoverOwnerStatus.GAP, reason(report.gaps() + " écart(s) hors tolérance : "
                + String.join(" ; ", checks)));
    }

    /** The French label of an error that stopped one owner (the exception's message is never shown). */
    public static String failure(RuntimeException e) {
        if (e instanceof DomainException d && d.getErrorCode() != null) {
            ErrorCode code = d.getErrorCode();
            if (code == ErrorCode.MIGRATION_ALREADY_RUNNING) {
                return "Un import de ce compte était déjà en cours : relancez la bascule";
            }
            if (code == ErrorCode.MIGRATION_OWNER_NOT_FOUND) {
                return "Compte absent de la base Bref restaurée (ou n'est plus propriétaire)";
            }
            if (code == ErrorCode.MIGRATION_EXPORT_INVALID) {
                return "Une ligne Bref de ce compte est illisible";
            }
            if (code == ErrorCode.MIGRATION_SOURCE_UNAVAILABLE) {
                return "Base Bref restaurée inaccessible : relancez la bascule";
            }
        }
        return UNEXPECTED;
    }

    /**
     * The counts (COUNTS lines) and sums (sales, collected, expenses, outstanding, credit) of a reconciliation report:
     * expected (from Bref) and Keevo values. Lines whose values are not numeric are skipped.
     */
    public static OwnerFigures figures(ReconciliationReport report) {
        if (report == null || report.checks().isEmpty()) {
            return OwnerFigures.NONE;
        }
        Map<String, Pair> counts = new LinkedHashMap<>();
        Map<String, Pair> sums = new LinkedHashMap<>();
        for (ReconciliationCheck check : report.checks()) {
            ReconciliationCheck.Kind kind = checkKind(check.code());
            if (kind == null) {
                continue;
            }
            for (ReconciliationLine line : check.lines()) {
                switch (kind) {
                    case COUNTS -> add(counts, line.label(), number(line.expected()), number(line.actual()));
                    case SALES_BY_STORE_MONTH -> add(sums, SALES, amount(line.expected()), amount(line.actual()));
                    case COLLECTED -> add(sums, COLLECTED, amount(line.expected()), amount(line.actual()));
                    case EXPENSES_BY_MONTH -> add(sums, EXPENSES, amount(line.expected()), amount(line.actual()));
                    case OUTSTANDING -> add(sums, OUTSTANDING, amount(line.expected()), amount(line.actual()));
                    case SUBSCRIPTION -> {
                        if (CREDIT.equals(line.label())) {
                            add(sums, CREDIT, amount(line.expected()), amount(line.actual()));
                        }
                    }
                    default -> {
                        // sample, links, anomalies: not summed
                    }
                }
            }
        }
        return new OwnerFigures(counts, sums, Map.of());
    }

    /** The anomalies an import batch noted (values kept as they were in Bref): French label → rows. */
    public static Map<String, Long> anomalies(MigrationBatch batch) {
        Map<String, Long> notes = new LinkedHashMap<>();
        batch.notes().forEach(n -> {
            if (n.label() != null && n.count() > 0) {
                notes.merge(n.label(), (long) n.count(), Long::sum);
            }
        });
        return notes;
    }

    private static void add(Map<String, Pair> into, String label, BigDecimal expected, BigDecimal actual) {
        if (label == null || expected == null || actual == null) {
            return;
        }
        into.merge(label, new Pair(expected, actual), Pair::plus);
    }

    /** An amount as {@code BrefReconciler.sum} writes it: plain decimal followed by « F ». */
    static BigDecimal amount(String value) {
        if (value == null || !value.endsWith(" F")) {
            return null;
        }
        return number(value.substring(0, value.length() - 2));
    }

    static BigDecimal number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BlockedCase.Kind kind(String code) {
        try {
            return code == null ? null : BlockedCase.Kind.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ReconciliationCheck.Kind checkKind(String code) {
        try {
            return code == null ? null : ReconciliationCheck.Kind.valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String reason(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= REASON_MAX ? text : text.substring(0, REASON_MAX - 1) + "…";
    }
}
