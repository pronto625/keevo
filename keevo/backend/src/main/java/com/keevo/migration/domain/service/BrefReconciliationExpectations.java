package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefMonetisation;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.ReconciliationFigures.Amount;
import com.keevo.migration.domain.model.ReconciliationFigures.Bucket;
import com.keevo.migration.domain.model.ReconciliationFigures.Expected;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedCount;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedLink;
import com.keevo.migration.domain.model.ReconciliationFigures.ExpectedSubscription;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordCount;
import com.keevo.migration.domain.model.ReconciliationFigures.RecordsSummary;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleDocument;
import com.keevo.migration.domain.model.ReconciliationFigures.SampleField;
import com.keevo.shared.domain.time.KeevoZones;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.keevo.migration.domain.model.ReconciliationFigures.CLIENTS;
import static com.keevo.migration.domain.model.ReconciliationFigures.DELETED_DOCUMENTS;
import static com.keevo.migration.domain.model.ReconciliationFigures.EXPENSES;
import static com.keevo.migration.domain.model.ReconciliationFigures.LINES;
import static com.keevo.migration.domain.model.ReconciliationFigures.OWNER;
import static com.keevo.migration.domain.model.ReconciliationFigures.PAYMENTS;
import static com.keevo.migration.domain.model.ReconciliationFigures.PRODUCTS;
import static com.keevo.migration.domain.model.ReconciliationFigures.STORES;
import static com.keevo.migration.domain.model.ReconciliationFigures.TEAM;
import static com.keevo.migration.domain.model.ReconciliationFigures.documents;

/**
 * Story 24.8 — what Keevo must hold for a Bref owner, computed from the raw Bref rows of the snapshot by the documented
 * import rules (spec 24.2–24.5), independently of the import's planners (pure, deterministic, no I/O). The figures of
 * analysis §10.3: counts after the documented merges and exclusions, Σ non-cancelled sales per shop and civil month,
 * Σ collected, Σ expenses per month, outstanding (Bref owner dashboard formula), a sample of 20 documents (5 largest
 * sales + 15 drawn with a seed), up to 5 public links, the subscription (ADR-07 rule, the plan planner's own function).
 *
 * <p>Cost: one pass over each section of the snapshot (O(n)), one sort of the sales for the numbering and of the sample
 * candidates (O(n log n)); hash maps for every lookup.
 */
public final class BrefReconciliationExpectations {

    public static final int SAMPLE_LARGEST = 5;
    public static final int SAMPLE_SIZE = 20;
    public static final int LINKS = 5;
    private static final Pattern CIVIL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> OPEN = Set.of("pending", "partial", "overdue");
    /** {@code BrefDocumentPlanner} limits and defaults (24.4). */
    static final int MAX_CLIENT_NAME = 200;
    static final String DEFAULT_CLIENT = "Client";

    private BrefReconciliationExpectations() {
    }

    /**
     * @param monetisation the owner's monetisation section, {@code null} to leave the subscription out
     * @param planInstant  the instant the import decided the plan (its {@code tenant_plan} record), {@code null} when
     *                     the import recorded none
     * @param freePlan     the Keevo free plan code
     * @param seed         the batch id: the same batch always draws the same sample
     */
    public static Expected expected(BrefSnapshot snapshot, BrefMonetisation monetisation, RecordsSummary records,
                                    Instant planInstant, String freePlan, UUID seed) {
        Map<UUID, String> storeLabels = storeLabels(snapshot.shops());
        Random random = new Random(seed.getMostSignificantBits() ^ seed.getLeastSignificantBits());
        return new Expected(counts(snapshot, records), sales(snapshot.invoices()), collected(snapshot.invoices()),
                expenses(snapshot.expenses()), outstanding(snapshot.invoices()),
                sample(snapshot.invoices(), random), links(snapshot.invoices(), random),
                subscription(monetisation, planInstant, freePlan), storeLabels);
    }

    /** « Boutique 1 », « Boutique 2 »… by Bref creation order (a shop name can be a person's name: never shown). */
    static Map<UUID, String> storeLabels(List<BrefShop> shops) {
        Map<UUID, String> labels = new HashMap<>();
        List<BrefShop> sorted = shops.stream()
                .sorted(Comparator.comparing(BrefShop::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BrefShop::id))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            labels.put(sorted.get(i).id(), "Boutique " + (i + 1));
        }
        return labels;
    }

    // ── counts ──────────────────────────────────────────────────────────────

    static List<ExpectedCount> counts(BrefSnapshot s, RecordsSummary records) {
        List<ExpectedCount> counts = new ArrayList<>();
        counts.add(new ExpectedCount(STORES, "Boutiques", s.shops().size(), 0, null));
        counts.add(new ExpectedCount(OWNER, "Propriétaire", 1, 0, null));

        long deleted = 0;
        long leftOut = 0;
        for (BrefAccount m : s.members()) {
            if (m.deleted()) {
                deleted++;
            } else if (!m.active() && !records.recordedAccounts().contains(m.id())) {
                leftOut++; // 24.7: deactivated in Bref and already in Keevo → not invited
            }
        }
        counts.add(new ExpectedCount(TEAM, "Membres de l'équipe (comptes et invitations)", s.members().size(),
                deleted + leftOut, explanation(
                        deleted, "supprimé(s) dans Bref, non importé(s)",
                        leftOut, "désactivé(s) dans Bref et déjà inscrit(s) dans Keevo, non invité(s)")));

        RecordCount products = records.get(MigrationRecord.BREF_PRODUCT, MigrationRecord.PRODUCTS);
        long merged = Math.max(0, products.rows() - products.targets());
        counts.add(new ExpectedCount(PRODUCTS, "Produits", s.products().size(), merged,
                explanation(merged, "regroupé(s) avec le même produit d'une autre boutique")));
        RecordCount clients = records.get(MigrationRecord.BREF_CUSTOMER, MigrationRecord.CLIENTS);
        long clientsMerged = Math.max(0, clients.rows() - clients.targets());
        counts.add(new ExpectedCount(CLIENTS, "Clients", s.customers().size(), clientsMerged,
                explanation(clientsMerged, "regroupé(s)")));

        Map<String, Long> docs = new LinkedHashMap<>();
        for (String key : List.of(documents("QUOTE", "DRAFT"), documents("QUOTE", "CANCELLED"),
                documents("INVOICE", "ISSUED"), documents("INVOICE", "PAID"), documents("INVOICE", "CANCELLED"),
                DELETED_DOCUMENTS)) {
            docs.put(key, 0L);
        }
        long lines = 0;
        long paymentRows = 0;
        long zero = 0;
        long quoteAcomptes = 0;
        for (BrefInvoice i : s.invoices()) {
            docs.merge(i.deleted() ? DELETED_DOCUMENTS : documents(kind(i), status(i)), 1L, Long::sum);
            lines += i.items().size();
            if (i.initialPayment().signum() > 0) {
                paymentRows++;
                if (i.quote()) {
                    quoteAcomptes++;
                } else if (franc(i.initialPayment()) == 0) {
                    zero++;
                }
            }
            for (BrefInvoicePayment p : i.payments()) {
                paymentRows++;
                if (franc(p.amount()) == 0) {
                    zero++;
                }
            }
        }
        docs.forEach((key, n) -> counts.add(new ExpectedCount(key, documentLabel(key), n, 0, null)));
        counts.add(new ExpectedCount(LINES, "Lignes de documents", lines, 0, null));
        counts.add(new ExpectedCount(PAYMENTS, "Encaissements et échéances (acomptes compris)", paymentRows,
                zero + quoteAcomptes, explanation(
                        zero, "de 0 F après arrondi, non repris",
                        quoteAcomptes, "acompte(s) sur devis (jamais encaissé(s) par Bref), noté(s)")));
        counts.add(new ExpectedCount(EXPENSES, "Dépenses (archivées comprises)", s.expenses().size(), 0, null));
        return counts;
    }

    /** French label of a document count key. */
    public static String documentLabel(String key) {
        return switch (key) {
            case "documents:QUOTE:DRAFT" -> "Devis en cours";
            case "documents:QUOTE:CANCELLED" -> "Devis annulés";
            case "documents:INVOICE:ISSUED" -> "Factures à encaisser";
            case "documents:INVOICE:PAID" -> "Factures payées";
            case "documents:INVOICE:CANCELLED" -> "Factures annulées";
            case DELETED_DOCUMENTS -> "Documents supprimés dans Bref (importés supprimés)";
            default -> "Autres documents (type ou statut inattendu)";
        };
    }

    private static String explanation(Object... pairs) {
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < pairs.length; k += 2) {
            long n = (Long) pairs[k];
            if (n > 0) {
                parts.add(n + " " + pairs[k + 1]);
            }
        }
        return parts.isEmpty() ? null : String.join(" ; ", parts);
    }

    // ── sums ────────────────────────────────────────────────────────────────

    /** Σ {@code total} of live non-cancelled sales per shop and civil month (Bref's {@code date}, else creation day). */
    static Map<Bucket, Amount> sales(List<BrefInvoice> invoices) {
        Map<Bucket, Amount> sales = new HashMap<>();
        for (BrefInvoice i : invoices) {
            if (i.quote() || i.deleted() || i.cancelled()) {
                continue;
            }
            Bucket bucket = new Bucket(BrefMappings.uuidOrNull(i.shopId()), issueDate(i).toString().substring(0, 7));
            sales.merge(bucket, Amount.ZERO.add(i.total()), Amount::plus);
        }
        return sales;
    }

    /** Σ acomptes of sales + Σ paid instalments (every document: the import keeps them all). */
    static Amount collected(List<BrefInvoice> invoices) {
        Amount collected = Amount.ZERO;
        for (BrefInvoice i : invoices) {
            if (!i.quote() && i.initialPayment().signum() > 0) {
                collected = collected.add(i.initialPayment());
            }
            for (BrefInvoicePayment p : i.payments()) {
                if (p.paid()) {
                    collected = collected.add(p.amount());
                }
            }
        }
        return collected;
    }

    /** Σ live expenses per month of {@code expenseDate} (exact decimals). */
    static Map<String, BigDecimal> expenses(List<BrefExpense> expenses) {
        Map<String, BigDecimal> months = new HashMap<>();
        for (BrefExpense e : expenses) {
            LocalDate day = civilDate(e.expenseDate());
            if (e.deletedAt() != null || day == null) {
                continue; // a dateless expense blocks the import (EXPENSE_INVALID): never reconciled
            }
            months.merge(day.toString().substring(0, 7), e.amount(), BigDecimal::add);
        }
        return months;
    }

    /**
     * Bref's owner dashboard: live sales {@code pending|partial|overdue}, Σ max(0, total − initialPayment − paid
     * instalments); items = the documents and the amounts subtracted (the tolerance basis).
     */
    static Amount outstanding(List<BrefInvoice> invoices) {
        BigDecimal sum = BigDecimal.ZERO;
        long items = 0;
        for (BrefInvoice i : invoices) {
            String status = i.status() == null ? "" : i.status().strip().toLowerCase(Locale.ROOT);
            if (i.quote() || i.deleted() || !OPEN.contains(status)) {
                continue;
            }
            BigDecimal remaining = i.total().subtract(i.initialPayment());
            items++;
            if (i.initialPayment().signum() > 0) {
                items++;
            }
            for (BrefInvoicePayment p : i.payments()) {
                if (p.paid()) {
                    remaining = remaining.subtract(p.amount());
                    items++;
                }
            }
            sum = sum.add(remaining.max(BigDecimal.ZERO));
        }
        return new Amount(sum, items);
    }

    // ── sample ──────────────────────────────────────────────────────────────

    /** The 5 largest sales (total, then id), then 15 other documents drawn with the batch's seed. */
    static List<SampleDocument> sample(List<BrefInvoice> invoices, Random random) {
        Map<UUID, String> numbers = numbers(invoices);
        Map<UUID, Integer> quoteNumbers = quoteNumbers(invoices);
        List<BrefInvoice> largest = invoices.stream().filter(i -> !i.quote())
                .sorted(Comparator.comparing(BrefInvoice::total).reversed().thenComparing(BrefInvoice::id))
                .limit(SAMPLE_LARGEST).toList();
        Set<UUID> taken = new java.util.HashSet<>();
        largest.forEach(i -> taken.add(i.id()));
        List<BrefInvoice> others = new ArrayList<>(invoices.stream().filter(i -> !taken.contains(i.id()))
                .sorted(Comparator.comparing(BrefInvoice::id)).toList());
        Collections.shuffle(others, random);
        List<BrefInvoice> picked = new ArrayList<>(largest);
        picked.addAll(others.subList(0, Math.min(others.size(), SAMPLE_SIZE - largest.size())));
        List<SampleDocument> sample = new ArrayList<>(picked.size());
        for (BrefInvoice i : picked) {
            sample.add(new SampleDocument(i.id(), label(i, numbers, quoteNumbers), fields(i, numbers.get(i.id()))));
        }
        return sample;
    }

    /** The display label of a document: its printed number, or « Devis n » (creation order). */
    static String label(BrefInvoice i, Map<UUID, String> numbers, Map<UUID, Integer> quoteNumbers) {
        return i.quote() ? "Devis " + quoteNumbers.get(i.id()) : "Facture " + numbers.get(i.id());
    }

    /** Keevo field values the import must have written for this Bref document (24.4 rules). */
    static Map<SampleField, String> fields(BrefInvoice i, String number) {
        Map<SampleField, String> f = new EnumMap<>(SampleField.class);
        boolean quote = i.quote();
        f.put(SampleField.KIND, kind(i));
        f.put(SampleField.STATUS, status(i));
        f.put(SampleField.NUMBER, quote || number == null ? "" : number);
        f.put(SampleField.ISSUE_DATE, quote ? "" : issueDate(i).toString());
        UUID store = BrefMappings.uuidOrNull(i.shopId());
        f.put(SampleField.STORE, store == null ? "" : store.toString());
        String name = BrefMappings.trimmed(i.customerName());
        f.put(SampleField.CLIENT_NAME, BrefMappings.cut(name == null ? DEFAULT_CLIENT : name, MAX_CLIENT_NAME));
        f.put(SampleField.SUBTOTAL, Long.toString(franc(i.subtotal())));
        f.put(SampleField.DISCOUNT, Long.toString(franc(i.discount())));
        f.put(SampleField.DELIVERY, Long.toString(franc(i.deliveryFee())));
        f.put(SampleField.TAX, Long.toString(franc(i.tax())));
        f.put(SampleField.TOTAL, Long.toString(franc(i.total())));
        f.put(SampleField.LINE_COUNT, Integer.toString(i.items().size()));
        long linesHt = 0;
        for (BrefInvoiceItem item : i.items()) {
            linesHt += franc(item.total());
        }
        f.put(SampleField.LINES_TOTAL, Long.toString(linesHt));
        long rows = 0;
        long paid = 0;
        long acompte = quote ? 0 : franc(i.initialPayment());
        if (acompte > 0) {
            rows++;
            paid += acompte;
        }
        for (BrefInvoicePayment p : i.payments()) {
            long amount = franc(p.amount());
            if (amount == 0) {
                continue;
            }
            rows++;
            if (p.paid()) {
                paid += amount;
            }
        }
        f.put(SampleField.PAYMENT_COUNT, Long.toString(rows));
        f.put(SampleField.PAID, Long.toString(paid));
        UUID token = i.deleted() ? null : BrefMappings.uuidOrNull(i.publicToken());
        f.put(SampleField.PUBLIC_LINK, token == null ? "" : token.toString());
        f.put(SampleField.DELETED, Boolean.toString(i.deleted()));
        return f;
    }

    /** {@code BRF-XXXXXXXX} per sale, oldest first; a prefix already used gets {@code -2}, {@code -3}… (24.4 rule). */
    static Map<UUID, String> numbers(List<BrefInvoice> invoices) {
        Map<String, Integer> used = new HashMap<>();
        Map<UUID, String> numbers = new HashMap<>();
        invoices.stream().filter(i -> !i.quote())
                .sorted(Comparator.comparing(BrefInvoice::createdAt).thenComparing(BrefInvoice::id))
                .forEach(i -> {
                    String base = BrefDocumentPlanner.legacyReference(i.id());
                    int n = used.merge(base, 1, Integer::sum);
                    numbers.put(i.id(), n == 1 ? base : base + "-" + n);
                });
        return numbers;
    }

    private static Map<UUID, Integer> quoteNumbers(List<BrefInvoice> invoices) {
        Map<UUID, Integer> numbers = new HashMap<>();
        int[] n = {0};
        invoices.stream().filter(BrefInvoice::quote)
                .sorted(Comparator.comparing(BrefInvoice::createdAt).thenComparing(BrefInvoice::id))
                .forEach(i -> numbers.put(i.id(), ++n[0]));
        return numbers;
    }

    // ── links ───────────────────────────────────────────────────────────────

    /** Up to 5 live documents with a readable Bref token, drawn with the batch's seed. */
    static List<ExpectedLink> links(List<BrefInvoice> invoices, Random random) {
        Map<UUID, String> numbers = numbers(invoices);
        Map<UUID, Integer> quoteNumbers = quoteNumbers(invoices);
        List<BrefInvoice> candidates = new ArrayList<>(invoices.stream()
                .filter(i -> !i.deleted() && BrefMappings.uuidOrNull(i.publicToken()) != null)
                .sorted(Comparator.comparing(BrefInvoice::id)).toList());
        Collections.shuffle(candidates, random);
        List<ExpectedLink> links = new ArrayList<>();
        for (BrefInvoice i : candidates.subList(0, Math.min(LINKS, candidates.size()))) {
            links.add(new ExpectedLink(BrefMappings.uuidOrNull(i.publicToken()), i.id(),
                    label(i, numbers, quoteNumbers)));
        }
        return links;
    }

    // ── subscription ────────────────────────────────────────────────────────

    /**
     * The plan in force when the import decided it (ADR-07, {@link BrefMonetisationPlanner#decidePlan}), and the
     * credit the import credited: Bref's positive credit + the paid scheduled periods of another plan.
     */
    static ExpectedSubscription subscription(BrefMonetisation m, Instant planInstant, String freePlan) {
        if (m == null || planInstant == null) {
            return null;
        }
        BrefMonetisationPlanner.PlanDecision decision = BrefMonetisationPlanner.decidePlan(m, planInstant, freePlan);
        long credit = Math.max(0, m.accountCredit() == null ? 0 : m.accountCredit().amount()) + decision.creditXaf();
        return new ExpectedSubscription(decision.planCode(), decision.expiresAt(), credit);
    }

    // ── rules shared with the import (24.4) ─────────────────────────────────

    static String kind(BrefInvoice i) {
        return i.quote() ? "QUOTE" : "INVOICE";
    }

    static String status(BrefInvoice i) {
        if (i.quote()) {
            return i.cancelled() ? "CANCELLED" : "DRAFT";
        }
        return i.cancelled() ? "CANCELLED" : i.paid() ? "PAID" : "ISSUED";
    }

    /** Bref's civil {@code date}, else the creation day in Africa/Douala (the import's fallback). */
    static LocalDate issueDate(BrefInvoice i) {
        LocalDate date = civilDate(i.date());
        return date != null ? date : LocalDate.ofInstant(i.createdAt(), KeevoZones.WAT);
    }

    static LocalDate civilDate(String raw) {
        String v = BrefMappings.trimmed(raw);
        if (v == null || !CIVIL_DATE.matcher(v).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The franc Keevo stores (HALF_UP). */
    static long franc(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
