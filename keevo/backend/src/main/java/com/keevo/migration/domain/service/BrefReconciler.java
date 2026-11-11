package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.ReconciliationCheck;
import com.keevo.migration.domain.model.ReconciliationCheck.Kind;
import com.keevo.migration.domain.model.ReconciliationFigures;
import com.keevo.migration.domain.model.ReconciliationFigures.Actual;
import com.keevo.migration.domain.model.ReconciliationFigures.ActualSubscription;
import com.keevo.migration.domain.model.ReconciliationFigures.Amount;
import com.keevo.migration.domain.model.ReconciliationFigures.Bucket;
import com.keevo.migration.domain.model.ReconciliationFigures.Expected;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedCount;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedLink;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedSubscription;
import com.keevo.migration.domain.model.ReconciliationFigures.LinkState;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleDocument;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleField;
import com.keevo.migration.domain.model.ReconciliationLine;
import com.keevo.migration.domain.model.ReconciliationLine.Status;
import com.keevo.shared.domain.time.KeevoZones;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.8 — compares what Bref gives with what Keevo holds, check by check (analysis §10.3), and labels every line in
 * French without ids nor personal data (pure function, no I/O). Tolerances (decision D2): counts, expenses, sample,
 * subscription and links 0; sales ±1 F per document of the bucket; collected and outstanding ±1 F per rounded amount
 * (each Bref amount with cents rounds by at most 0.5 F). A non-zero difference within tolerance is EXPLAINED (rounding),
 * beyond it a GAP.
 *
 * <p>Cost: O(n) over the figures (bucket and month unions through hash sets, a sort of the bucket keys for a stable
 * report).
 */
public final class BrefReconciler {

    private static final Locale FR = Locale.FRENCH;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", FR);
    private static final String ROUNDING = "Arrondis au franc des montants Bref avec centimes";

    private BrefReconciler() {
    }

    /**
     * @param notedByTable rows the import wrote with a note, per Keevo target table ({@code migration_records.note})
     * @param planLabel    French label of a plan code (the code itself when unknown)
     */
    public static List<ReconciliationCheck> reconcile(Expected expected, Actual actual, Map<String, Long> notedByTable,
                                                      Function<String, String> planLabel) {
        List<ReconciliationCheck> checks = new ArrayList<>();
        checks.add(ReconciliationCheck.of(Kind.COUNTS, counts(expected.counts(), actual.counts())));
        checks.add(ReconciliationCheck.of(Kind.SALES_BY_STORE_MONTH,
                sales(expected.sales(), actual.sales(), expected.storeLabels())));
        checks.add(ReconciliationCheck.of(Kind.COLLECTED,
                List.of(amount("Total encaissé", expected.collected(), actual.collected()))));
        checks.add(ReconciliationCheck.of(Kind.EXPENSES_BY_MONTH, expenses(expected.expenses(), actual.expenses())));
        checks.add(ReconciliationCheck.of(Kind.OUTSTANDING,
                List.of(amount("Restant dû total", expected.outstanding(), actual.outstanding()))));
        checks.add(ReconciliationCheck.of(Kind.SAMPLE, sample(expected.sample(), actual.sample(),
                expected.storeLabels())));
        checks.add(ReconciliationCheck.of(Kind.SUBSCRIPTION, subscription(expected.subscription(),
                actual.subscription(), planLabel)));
        checks.add(ReconciliationCheck.of(Kind.PUBLIC_LINKS, links(expected.links(), actual.links())));
        checks.add(ReconciliationCheck.of(Kind.NOTED_ANOMALIES, noted(notedByTable)));
        return checks;
    }

    // ── counts ──────────────────────────────────────────────────────────────

    static List<ReconciliationLine> counts(List<ExpectedCount> expected, Map<String, Long> actual) {
        List<ReconciliationLine> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExpectedCount c : expected) {
            seen.add(c.key());
            long keevo = actual.getOrDefault(c.key(), 0L);
            long diff = keevo - c.expected();
            Status status = diff != 0 ? Status.GAP : c.explained() > 0 ? Status.EXPLAINED : Status.OK;
            String explanation = c.explanation();
            if (diff != 0) {
                explanation = (diff > 0 ? diff + " ligne(s) de plus" : -diff + " ligne(s) manquante(s)")
                        + " dans Keevo (Bref : " + c.bref() + ")" + (explanation == null ? "" : " ; " + explanation);
            } else if (explanation != null) {
                explanation = "Bref : " + c.bref() + " ; " + explanation;
            }
            lines.add(ReconciliationLine.of(c.label(), Long.toString(c.expected()), Long.toString(keevo),
                    BigDecimal.valueOf(diff), BigDecimal.ZERO, status, explanation));
        }
        // documents of a kind or status the import never writes
        long others = actual.entrySet().stream()
                .filter(e -> e.getKey().startsWith(ReconciliationFigures.DOCUMENTS) && !seen.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        if (others > 0) {
            lines.add(ReconciliationLine.of(BrefReconciliationExpectations.documentLabel("other"), "0",
                    Long.toString(others), BigDecimal.valueOf(others), BigDecimal.ZERO, Status.GAP,
                    others + " document(s) d'un type ou d'un statut que l'import n'écrit pas"));
        }
        return lines;
    }

    // ── sums ────────────────────────────────────────────────────────────────

    static List<ReconciliationLine> sales(Map<Bucket, Amount> expected, Map<Bucket, Amount> actual,
                                          Map<UUID, String> storeLabels) {
        Set<Bucket> keys = new HashSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        List<Bucket> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparing((Bucket b) -> storeLabel(b.store(), storeLabels))
                .thenComparing(Bucket::month));
        List<ReconciliationLine> lines = new ArrayList<>();
        for (Bucket b : sorted) {
            Amount e = expected.getOrDefault(b, Amount.ZERO);
            Amount a = actual.getOrDefault(b, Amount.ZERO);
            lines.add(sum(storeLabel(b.store(), storeLabels) + " — " + month(b.month()), e.sum(), a.sum(),
                    BigDecimal.valueOf(Math.max(e.items(), a.items())),
                    e.items() + " vente(s) dans Bref, " + a.items() + " dans Keevo"));
        }
        return lines;
    }

    static ReconciliationLine amount(String label, Amount expected, Amount actual) {
        return sum(label, expected.sum(), actual.sum(), BigDecimal.valueOf(Math.max(expected.items(),
                actual.items())), null);
    }

    static List<ReconciliationLine> expenses(Map<String, BigDecimal> expected, Map<String, BigDecimal> actual) {
        Set<String> months = new TreeSet<>(expected.keySet());
        months.addAll(actual.keySet());
        List<ReconciliationLine> lines = new ArrayList<>();
        for (String m : months) {
            lines.add(sum(month(m), expected.getOrDefault(m, BigDecimal.ZERO), actual.getOrDefault(m,
                    BigDecimal.ZERO), BigDecimal.ZERO, null));
        }
        return lines;
    }

    /** A numeric line: OK when equal, EXPLAINED (rounding) within tolerance, else GAP. */
    static ReconciliationLine sum(String label, BigDecimal expected, BigDecimal actual, BigDecimal tolerance,
                                  String detail) {
        BigDecimal diff = actual.subtract(expected);
        Status status = diff.signum() == 0 ? Status.OK
                : diff.abs().compareTo(tolerance) <= 0 ? Status.EXPLAINED : Status.GAP;
        String explanation = switch (status) {
            case OK -> detail;
            case EXPLAINED -> ROUNDING + (detail == null ? "" : " ; " + detail);
            case GAP -> "Écart de " + plain(diff) + " F au-delà de la tolérance de " + plain(tolerance) + " F"
                    + (detail == null ? "" : " ; " + detail);
        };
        return ReconciliationLine.of(label, plain(expected) + " F", plain(actual) + " F", stripped(diff),
                stripped(tolerance), status, explanation);
    }

    // ── sample ──────────────────────────────────────────────────────────────

    static List<ReconciliationLine> sample(List<SampleDocument> expected, Map<UUID, Map<SampleField, String>> actual,
                                           Map<UUID, String> storeLabels) {
        List<ReconciliationLine> lines = new ArrayList<>();
        for (SampleDocument d : expected) {
            Map<SampleField, String> keevo = actual.get(d.id());
            if (keevo == null) {
                lines.add(ReconciliationLine.of(d.label(), "Présent", "Absent", null, null, Status.GAP,
                        "Document absent de Keevo"));
                continue;
            }
            List<String> fields = new ArrayList<>();
            List<String> exp = new ArrayList<>();
            List<String> act = new ArrayList<>();
            for (Map.Entry<SampleField, String> f : d.fields().entrySet()) {
                String want = f.getValue() == null ? "" : f.getValue();
                String got = keevo.getOrDefault(f.getKey(), "");
                if (!want.equals(got)) {
                    fields.add(f.getKey().label());
                    exp.add(f.getKey().label() + " : " + display(f.getKey(), want, storeLabels));
                    act.add(f.getKey().label() + " : " + display(f.getKey(), got, storeLabels));
                }
            }
            if (fields.isEmpty()) {
                lines.add(ReconciliationLine.of(d.label(), d.fields().size() + " champs", "identiques", null, null,
                        Status.OK, null));
            } else {
                lines.add(ReconciliationLine.of(d.label(), String.join(" ; ", exp), String.join(" ; ", act), null,
                        null, Status.GAP, "Champ(s) différent(s) : " + String.join(", ", fields)));
            }
        }
        return lines;
    }

    /** A field value as the report shows it; a personal or technical value is never shown. */
    static String display(SampleField field, String raw, Map<UUID, String> storeLabels) {
        if (field.hidden()) {
            return raw.isEmpty() ? "aucun" : "différent";
        }
        if (raw.isEmpty()) {
            return "aucun";
        }
        return switch (field) {
            case KIND -> "QUOTE".equals(raw) ? "Devis" : "INVOICE".equals(raw) ? "Facture" : "autre";
            case STATUS -> switch (raw) {
                case "DRAFT" -> "En cours";
                case "ISSUED" -> "À encaisser";
                case "PAID" -> "Payée";
                case "CANCELLED" -> "Annulé";
                default -> "autre";
            };
            case STORE -> {
                UUID id = parse(raw);
                yield id == null ? "inconnue" : storeLabel(id, storeLabels);
            }
            case DELETED -> "true".equals(raw) ? "oui" : "non";
            case ISSUE_DATE -> {
                try {
                    yield LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", FR));
                } catch (RuntimeException e) {
                    yield "illisible";
                }
            }
            case SUBTOTAL, DISCOUNT, DELIVERY, TAX, TOTAL, LINES_TOTAL, PAID -> raw + " F";
            default -> raw;
        };
    }

    // ── subscription ────────────────────────────────────────────────────────

    static List<ReconciliationLine> subscription(ExpectedSubscription expected, ActualSubscription actual,
                                                 Function<String, String> planLabel) {
        if (expected == null) {
            return List.of(ReconciliationLine.of("Plan en vigueur à la migration", "Décidé par l'import",
                    "Non trouvé", null, null, Status.GAP, "L'import n'a pas enregistré de plan pour ce compte"));
        }
        List<ReconciliationLine> lines = new ArrayList<>();
        String keevoPlan = actual == null ? null : actual.planCode();
        lines.add(ReconciliationLine.of("Plan", planLabel.apply(expected.planCode()),
                keevoPlan == null ? "aucun" : planLabel.apply(keevoPlan), null, null,
                Objects.equals(expected.planCode(), keevoPlan) ? Status.OK : Status.GAP, null));
        Instant want = micros(expected.expiresAt());
        Instant got = actual == null ? null : micros(actual.expiresAt());
        lines.add(ReconciliationLine.of("Échéance", dateTime(want), dateTime(got), null, null,
                Objects.equals(want, got) ? Status.OK : Status.GAP, null));
        long credit = actual == null ? 0 : actual.creditXaf();
        BigDecimal diff = BigDecimal.valueOf(credit - expected.creditXaf());
        lines.add(ReconciliationLine.of("Crédit du compte", expected.creditXaf() + " F", credit + " F", diff,
                BigDecimal.ZERO, diff.signum() == 0 ? Status.OK : Status.GAP, null));
        return lines;
    }

    private static Instant micros(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
    }

    private static String dateTime(Instant at) {
        return at == null ? "sans échéance" : DATE_TIME.format(at.atZone(KeevoZones.WAT)) + " (heure de Douala)";
    }

    // ── links ───────────────────────────────────────────────────────────────

    static List<ReconciliationLine> links(List<ExpectedLink> expected, Map<UUID, LinkState> actual) {
        if (expected.isEmpty()) {
            return List.of(ReconciliationLine.of("Aucun document avec un lien public Bref", "0", "0", null, null,
                    Status.OK, null));
        }
        List<ReconciliationLine> lines = new ArrayList<>();
        for (ExpectedLink l : expected) {
            LinkState s = actual.get(l.token());
            String got;
            boolean ok = false;
            if (s == null) {
                got = "Lien absent";
            } else if (!s.sameTenant() || !l.documentId().equals(s.documentId())) {
                got = "Lien vers un autre document";
            } else if (!s.documentLive()) {
                got = "Document absent ou supprimé";
            } else {
                got = "Lien actif vers ce document";
                ok = true;
            }
            lines.add(ReconciliationLine.of("Lien de " + l.label(), "Lien actif vers ce document", got, null, null,
                    ok ? Status.OK : Status.GAP, ok ? null : "L'ancien lien Bref ne mènerait pas à ce document"));
        }
        return lines;
    }

    // ── noted anomalies ─────────────────────────────────────────────────────

    /** Rows the import kept as Bref had them and noted, per kind of row: explained, never a gap. */
    static List<ReconciliationLine> noted(Map<String, Long> notedByTable) {
        Map<String, Long> byLabel = new LinkedHashMap<>();
        new TreeSet<>(notedByTable.keySet()).forEach(table ->
                byLabel.merge(tableLabel(table), notedByTable.get(table), Long::sum));
        List<ReconciliationLine> lines = new ArrayList<>();
        byLabel.forEach((label, n) -> {
            if (n > 0) {
                lines.add(ReconciliationLine.of(label, "—", n + " ligne(s) avec une remarque", null, null,
                        Status.EXPLAINED, "Valeurs Bref conservées telles quelles et notées à l'import (détail dans "
                                + "les remarques du lot)"));
            }
        });
        return lines;
    }

    static String tableLabel(String table) {
        return switch (table) {
            case "invoices" -> "Documents";
            case "invoice_items" -> "Lignes de documents";
            case "invoice_payments" -> "Encaissements et échéances";
            case "expenses" -> "Dépenses";
            case "products", "categories" -> "Catalogue";
            case "clients" -> "Clients";
            case "stores", "store_logo", "invoicing_settings" -> "Boutiques";
            case "users", "linked_users", "membership_invitations", "user_tenant_memberships", "employees",
                 "user_authenticators", "tenants" -> "Comptes et équipe";
            default -> "Abonnement et paiements";
        };
    }

    // ── formatting ──────────────────────────────────────────────────────────

    static String storeLabel(UUID store, Map<UUID, String> storeLabels) {
        String label = store == null ? null : storeLabels.get(store);
        return label != null ? label : "Boutique inconnue";
    }

    /** « mars 2026 » for {@code 2026-03}. */
    static String month(String yearMonth) {
        try {
            java.time.YearMonth ym = java.time.YearMonth.parse(yearMonth);
            return ym.getMonth().getDisplayName(TextStyle.FULL, FR) + " " + ym.getYear();
        } catch (RuntimeException e) {
            return "mois inconnu";
        }
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal stripped(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s; // 1E+2 → 100 (plain in the JSON report)
    }
}
