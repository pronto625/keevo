package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.ReconciliationCheck;
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
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.ReconciliationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("24.8 — reconciler (Bref vs Keevo figures)")
class BrefReconcilerTest {

    private static final UUID SHOP = UUID.randomUUID();
    private static final Map<UUID, String> STORES = Map.of(SHOP, "Boutique 1");
    private static final Instant END = Instant.parse("2026-12-01T09:00:00.123456789Z");
    private static final Function<String, String> PLAN = code -> "Plan " + code.toLowerCase();

    private static Map<SampleField, String> fields(String total, String name) {
        Map<SampleField, String> f = new EnumMap<>(SampleField.class);
        f.put(SampleField.KIND, "INVOICE");
        f.put(SampleField.STATUS, "PAID");
        f.put(SampleField.STORE, SHOP.toString());
        f.put(SampleField.ISSUE_DATE, "2026-03-02");
        f.put(SampleField.TOTAL, total);
        f.put(SampleField.CLIENT_NAME, name);
        return f;
    }

    private static Expected expected(UUID doc, UUID token) {
        return new Expected(
                List.of(new ExpectedCount(ReconciliationFigures.PRODUCTS, "Produits", 5, 2, "2 regroupé(s)"),
                        new ExpectedCount(ReconciliationFigures.STORES, "Boutiques", 2, 0, null)),
                Map.of(new Bucket(SHOP, "2026-03"), new Amount(new BigDecimal("1600.50"), 2)),
                new Amount(new BigDecimal("600.50"), 3),
                Map.of("2026-03", new BigDecimal("15100.50")),
                new Amount(new BigDecimal("550.5"), 6),
                List.of(new SampleDocument(doc, "Facture BRF-ABCDEF12", fields("1501", "Awa Test"))),
                List.of(new ExpectedLink(token, doc, "Facture BRF-ABCDEF12")),
                new ExpectedSubscription("PRO", END, 1200), STORES);
    }

    private static Actual actual(UUID doc, UUID token, long products, String march, String collected,
                                 String expenses, String total, LinkState link, ActualSubscription subscription,
                                 Map<String, Long> extra) {
        Map<String, Long> counts = new HashMap<>(Map.of(ReconciliationFigures.PRODUCTS, products,
                ReconciliationFigures.STORES, 2L));
        counts.putAll(extra);
        return new Actual(counts, Map.of(new Bucket(SHOP, "2026-03"), new Amount(new BigDecimal(march), 2)),
                new Amount(new BigDecimal(collected), 3), Map.of("2026-03", new BigDecimal(expenses)),
                new Amount(new BigDecimal("551"), 6), Map.of(doc, fields(total, "Awa Test")),
                link == null ? Map.of() : Map.of(token, link), subscription);
    }

    private static ReconciliationCheck check(List<ReconciliationCheck> checks, ReconciliationCheck.Kind kind) {
        return checks.stream().filter(c -> c.code().equals(kind.name())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("clean import: merges and rounding explained, notes listed, RECONCILED")
    void clean() {
        UUID doc = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        List<ReconciliationCheck> checks = BrefReconciler.reconcile(expected(doc, token),
                actual(doc, token, 3, "1601", "601", "15100.50", "1501", new LinkState(doc, true, true),
                        new ActualSubscription("PRO", END.plusNanos(1), 1200), Map.of()),
                Map.of("invoices", 2L, "invoice_payments", 1L, "products", 1L), PLAN);

        ReconciliationCheck counts = check(checks, ReconciliationCheck.Kind.COUNTS);
        assertThat(counts.lines()).extracting(ReconciliationLine::status).containsExactly(Status.EXPLAINED,
                Status.OK);
        assertThat(counts.lines().get(0).explanation()).contains("Bref : 5", "regroupé");
        ReconciliationLine sale = check(checks, ReconciliationCheck.Kind.SALES_BY_STORE_MONTH).lines().get(0);
        assertThat(sale.label()).isEqualTo("Boutique 1 — mars 2026");
        assertThat(sale.status()).isEqualTo(Status.EXPLAINED);
        assertThat(sale.difference()).isEqualByComparingTo("0.5");
        assertThat(sale.tolerance()).isEqualByComparingTo("2");
        assertThat(check(checks, ReconciliationCheck.Kind.EXPENSES_BY_MONTH).status()).isEqualTo(Status.OK);
        assertThat(check(checks, ReconciliationCheck.Kind.SAMPLE).status()).isEqualTo(Status.OK);
        assertThat(check(checks, ReconciliationCheck.Kind.SUBSCRIPTION).status()).isEqualTo(Status.OK);
        assertThat(check(checks, ReconciliationCheck.Kind.PUBLIC_LINKS).status()).isEqualTo(Status.OK);
        ReconciliationCheck noted = check(checks, ReconciliationCheck.Kind.NOTED_ANOMALIES);
        assertThat(noted.lines()).extracting(ReconciliationLine::label)
                .containsExactlyInAnyOrder("Catalogue", "Documents", "Encaissements et échéances");
        assertThat(noted.status()).isEqualTo(Status.EXPLAINED);

        ReconciliationReport report = ReconciliationReport.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), END, END, checks);
        assertThat(report.status()).isEqualTo(ReconciliationStatus.RECONCILED);
        assertThat(report.gaps()).isZero();
        assertThat(report.explained()).isPositive();
    }

    @Test
    @DisplayName("tampered Keevo: missing product, total beyond tolerance, expenses 1 cent off, sample, plan, link → FAILED")
    void tampered() {
        UUID doc = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        List<ReconciliationCheck> checks = BrefReconciler.reconcile(expected(doc, token),
                actual(doc, token, 2, "1610.50", "600.50", "15100.49", "1511", new LinkState(UUID.randomUUID(),
                                true, true), new ActualSubscription("FREE", null, 0),
                        Map.of(ReconciliationFigures.documents("INVOICE", "DRAFT"), 1L)),
                Map.of(), PLAN);

        ReconciliationCheck counts = check(checks, ReconciliationCheck.Kind.COUNTS);
        assertThat(counts.lines().get(0).status()).isEqualTo(Status.GAP);
        assertThat(counts.lines().get(0).explanation()).startsWith("1 ligne(s) manquante(s)");
        assertThat(counts.lines()).extracting(ReconciliationLine::label)
                .contains("Autres documents (type ou statut inattendu)");
        ReconciliationLine sale = check(checks, ReconciliationCheck.Kind.SALES_BY_STORE_MONTH).lines().get(0);
        assertThat(sale.status()).isEqualTo(Status.GAP);
        assertThat(sale.explanation()).contains("Écart de 10 F");
        assertThat(check(checks, ReconciliationCheck.Kind.EXPENSES_BY_MONTH).status()).isEqualTo(Status.GAP);
        ReconciliationLine sample = check(checks, ReconciliationCheck.Kind.SAMPLE).lines().get(0);
        assertThat(sample.status()).isEqualTo(Status.GAP);
        assertThat(sample.expected()).isEqualTo("Total TTC : 1501 F");
        assertThat(sample.actual()).isEqualTo("Total TTC : 1511 F");
        ReconciliationCheck subscription = check(checks, ReconciliationCheck.Kind.SUBSCRIPTION);
        assertThat(subscription.lines()).extracting(ReconciliationLine::status)
                .containsExactly(Status.GAP, Status.GAP, Status.GAP);
        assertThat(subscription.lines().get(0).expected()).isEqualTo("Plan pro");
        assertThat(subscription.lines().get(1).actual()).isEqualTo("sans échéance");
        assertThat(check(checks, ReconciliationCheck.Kind.PUBLIC_LINKS).lines().get(0).actual())
                .isEqualTo("Lien vers un autre document");
        assertThat(ReconciliationReport.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), END, END, checks).status()).isEqualTo(ReconciliationStatus.RECONCILIATION_FAILED);
    }

    @Test
    @DisplayName("sample: a missing document and a personal field never shown; links absent or deleted")
    void sampleAndLinks() {
        UUID doc = UUID.randomUUID();
        Map<SampleField, String> keevo = fields("1501", "Autre nom");
        List<ReconciliationLine> lines = BrefReconciler.sample(List.of(
                new SampleDocument(doc, "Facture BRF-1", fields("1501", "Awa Test")),
                new SampleDocument(UUID.randomUUID(), "Devis 1", fields("10", "X"))), Map.of(doc, keevo), STORES);

        assertThat(lines.get(0).status()).isEqualTo(Status.GAP);
        assertThat(lines.get(0).expected()).isEqualTo("Nom du client : différent").doesNotContain("Awa");
        assertThat(lines.get(0).actual()).doesNotContain("Autre");
        assertThat(lines.get(1).actual()).isEqualTo("Absent");

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        List<ReconciliationLine> links = BrefReconciler.links(List.of(new ExpectedLink(t1, doc, "Facture BRF-1"),
                new ExpectedLink(t2, doc, "Facture BRF-1")), Map.of(t2, new LinkState(doc, true, false)));
        assertThat(links).extracting(ReconciliationLine::actual)
                .containsExactly("Lien absent", "Document absent ou supprimé");
        assertThat(BrefReconciler.links(List.of(), Map.of())).singleElement()
                .extracting(ReconciliationLine::status).isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("no plan recorded by the import → gap; display of store, status, date and amounts in French")
    void subscriptionMissingAndDisplay() {
        assertThat(BrefReconciler.subscription(null, null, PLAN)).singleElement()
                .extracting(ReconciliationLine::status).isEqualTo(Status.GAP);
        assertThat(BrefReconciler.display(SampleField.STORE, SHOP.toString(), STORES)).isEqualTo("Boutique 1");
        assertThat(BrefReconciler.display(SampleField.STORE, UUID.randomUUID().toString(), STORES))
                .isEqualTo("Boutique inconnue");
        assertThat(BrefReconciler.display(SampleField.STATUS, "ISSUED", STORES)).isEqualTo("À encaisser");
        assertThat(BrefReconciler.display(SampleField.ISSUE_DATE, "2026-03-02", STORES)).isEqualTo("02/03/2026");
        assertThat(BrefReconciler.display(SampleField.PUBLIC_LINK, "", STORES)).isEqualTo("aucun");
        assertThat(BrefReconciler.display(SampleField.TOTAL, "100", STORES)).isEqualTo("100 F");
        assertThat(BrefReconciler.month("2026-11")).isEqualTo("novembre 2026");
        ReconciliationLine big = BrefReconciler.sum("x", new BigDecimal("100"), new BigDecimal("300"),
                BigDecimal.ZERO, null);
        assertThat(big.difference().toPlainString()).isEqualTo("200");
        assertThat(big.expected()).isEqualTo("100 F");
    }

    @Test
    @DisplayName("a reconciliation that could not run is failed, with its label and no checks")
    void failedReport() {
        ReconciliationReport r = ReconciliationReport.failed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, END, END, "La source Bref n'est pas accessible");
        assertThat(r.status()).isEqualTo(ReconciliationStatus.RECONCILIATION_FAILED);
        assertThat(r.statusLabel()).isEqualTo("Écart à corriger avant l'activation");
        assertThat(r.checks()).isEmpty();
    }
}
