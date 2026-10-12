package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.catalog.stock.adapter.out.export.StockLevelsExportDatasetProvider;
import com.keevo.commerce.sale.adapter.out.export.DayClosuresExportDatasetProvider;
import com.keevo.commerce.sale.adapter.out.export.SalesExportDatasetProvider;
import com.keevo.commerce.sale.domain.model.ClosureWindow;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.inventory.counting.adapter.out.export.InventoryCountsExportDatasetProvider;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PdfExportRenderer (v1s-18-3)")
class PdfExportRendererTest {

    private static final String TENANT = "kv_pdf001";
    private static final Instant NOW = Instant.parse("2026-10-12T12:30:00Z"); // 13:30 WAT
    private static final Instant DAY = Instant.parse("2026-10-11T09:00:00Z");
    private static final UUID STORE = UUID.randomUUID();

    private final DayClosureRepository closures = mock(DayClosureRepository.class);
    private final EndOfDayReportBuilder reportBuilder = mock(EndOfDayReportBuilder.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ExportProperties props = new ExportProperties();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        when(tenants.findBySchemaName(TENANT)).thenReturn(Optional.of(
                new Tenant(UUID.randomUUID(), "KV-PDF001", TENANT, "Épicerie Ngono", TenantStatus.ACTIVE, PlanType.PREMIUM, NOW)));
        when(stores.findById(STORE)).thenReturn(Optional.of(
                new Store(STORE, "Boutique Akwa", StoreType.STORE, null, null, true, NOW, NOW)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private PdfExportRenderer renderer() {
        return new PdfExportRenderer(new PdfDocumentFactory(), new EndOfDayPdfModel(closures, reportBuilder),
                tenants, stores, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── synthetic providers with the real column schemas ───────────────────────────

    /** Provider serving {@code total} generated rows of a real dataset's column schema, by page. */
    static class ListProvider implements ExportDatasetProvider {
        final ExportDatasetProvider schema;
        final int total;
        final Function<Integer, Map<String, Object>> rowFactory;
        final AtomicInteger rowsServed = new AtomicInteger();

        ListProvider(ExportDatasetProvider schema, int total, Function<Integer, Map<String, Object>> rowFactory) {
            this.schema = schema;
            this.total = total;
            this.rowFactory = rowFactory;
        }

        @Override public String key() { return schema.key(); }
        @Override public String label() { return schema.label(); }
        @Override public List<ExportColumn> columns() { return schema.columns(); }
        @Override public long count(ExportFilter filter) { return total; }

        @Override
        public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(total, start + limit);
            List<List<Object>> rows = new ArrayList<>();
            for (int i = start; i < end; i++) {
                Map<String, Object> byKey = rowFactory.apply(i);
                List<Object> row = new ArrayList<>();
                for (ExportColumn c : columns()) row.add(byKey.get(c.key()));
                rows.add(row);
                rowsServed.incrementAndGet();
            }
            return new ExportPage(rows, end < total ? Integer.toString(end) : null);
        }
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static ListProvider sales(int total) {
        return new ListProvider(new SalesExportDatasetProvider(null), total, i -> row(
                "id", UUID.randomUUID().toString(), "occurredAt", DAY.plusSeconds(60L * i), "store", "Boutique Akwa",
                "employee", "Awa Mbarga", "client", i == 0 ? "<b>Éloïse & Fils</b>" : "Client " + i,
                "status", i % 10 == 9 ? "Annulée" : "Validée",
                "paymentMode", i % 2 == 0 ? "Espèces" : "Mobile Money",
                "itemCount", 2L, "discount", 0L, "total", 12_500L));
    }

    // ── rendering helpers ───────────────────────────────────────────────────────────

    record Rendered(byte[] bytes, long rows, List<String> pages) {
        String all() { return String.join("\n", pages); }
    }

    private Rendered render(ExportDatasetProvider provider, ExportFilter filter) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long rows = renderer().render(out, List.of(provider), filter, new AtomicLong());
        byte[] bytes = out.toByteArray();
        List<String> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc).replace(' ', ' '));
                PDRectangle box = doc.getPage(p - 1).getMediaBox();
                assertThat(box.getWidth()).as("A4 width").isCloseTo(PDRectangle.A4.getWidth(), org.assertj.core.data.Offset.offset(1f));
                assertThat(box.getHeight()).as("A4 height").isCloseTo(PDRectangle.A4.getHeight(), org.assertj.core.data.Offset.offset(1f));
            }
        }
        return new Rendered(bytes, rows, pages);
    }

    private static ExportFilter period(UUID store) {
        return new ExportFilter(store, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-11T22:59:59Z"));
    }

    // ── tests ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("format PDF, application/pdf, .pdf, template labels from the catalogue")
    void metadata() {
        PdfExportRenderer r = renderer();
        assertThat(r.format()).isEqualTo(ExportFormat.PDF);
        assertThat(r.contentType(1)).isEqualTo("application/pdf");
        assertThat(r.extension(1)).isEqualTo("pdf");
        assertThat(r.templateLabel("day_closures")).contains("Fin de journée");
        assertThat(r.templateLabel("inventory_counts")).contains("Rapport d'inventaire");
        assertThat(r.templateLabel("stock_levels")).contains("État de stock");
        assertThat(r.templateLabel("sales")).contains("Liste des ventes");
        assertThat(r.templateLabel("products")).isEmpty();
    }

    @Test
    @DisplayName("Liste des ventes, multi-page: brand header + table header on every page, « Page x / N », totals in XAF, escaped text")
    void salesMultiPage() throws IOException {
        Rendered pdf = render(sales(120), period(null));

        assertThat(pdf.rows()).isEqualTo(120);
        int n = pdf.pages().size();
        assertThat(n).isGreaterThan(1);
        for (int p = 0; p < n; p++) {
            String page = pdf.pages().get(p);
            assertThat(page).contains("Liste des ventes", "Épicerie Ngono", "Toutes les boutiques",
                    "Du 01/10/2026 au 11/10/2026", "Généré le 12/10/2026 à 13:30", "Page " + (p + 1) + " / " + n);
        }
        // the sales table header is repeated on page 2
        assertThat(pdf.pages().get(1)).contains("Vendeur", "Client", "Statut", "Montant");
        String all = pdf.all();
        assertThat(all).contains("<b>Éloïse & Fils</b>"); // th:text escapes: shown literally
        assertThat(all).contains("Totaux par statut", "Validée", "Annulée", "Ventes validées par mode de paiement",
                "Espèces", "Mobile Money", "Total des ventes validées");
        // 108 validated × 12 500 = 1 350 000 ; 12 cancelled × 12 500 = 150 000
        assertThat(all).contains("12 500 XAF", "1 350 000 XAF", "150 000 XAF");
    }

    @Test
    @DisplayName("Liste des ventes for one store: header names the store, no store column")
    void salesOneStore() throws IOException {
        Rendered pdf = render(sales(3), period(STORE));
        assertThat(pdf.pages()).hasSize(1);
        assertThat(pdf.all()).contains("Boutique Akwa", "Page 1 / 1").doesNotContain("Toutes les boutiques");
    }

    @Test
    @DisplayName("État de stock: grouped by store, value per store and grand total, « Situation au … »")
    void stock() throws IOException {
        String s1 = UUID.randomUUID().toString();
        String s2 = UUID.randomUUID().toString();
        ListProvider provider = new ListProvider(new StockLevelsExportDatasetProvider(null), 4, i -> row(
                "storeId", i < 2 ? s1 : s2, "store", i < 2 ? "Akwa" : "Bonapriso", "productId", "p" + i,
                "sku", "SKU-" + i, "product", "Crème " + i, "category", "Hygiène", "quantity", (long) (i * 5),
                "minimumThreshold", 3L, "stockStatus", i == 0 ? "Rupture" : "OK", "buyPrice", 1000L,
                "stockValue", i * 5 * 1000L, "productArchived", i == 3, "updatedAt", DAY));

        String all = render(provider, ExportFilter.none()).all();

        assertThat(all).contains("État de stock", "Situation au 12/10/2026 à 13:30", "Akwa", "Bonapriso",
                "Crème 1", "Crème 3 (archivé)", "Rupture", "Valeur du stock — Akwa", "5 000 XAF",
                "Valeur du stock — Bonapriso", "25 000 XAF", "Valeur totale du stock", "30 000 XAF");
    }

    @Test
    @DisplayName("Rapport d'inventaire: one block per session, gaps signed, uncounted « — », session totals")
    void inventory() throws IOException {
        String sA = UUID.randomUUID().toString();
        String sB = UUID.randomUUID().toString();
        long[][] counts = {{10, 8}, {4, 6}, {5, -1}, {7, 7}};
        ListProvider provider = new ListProvider(new InventoryCountsExportDatasetProvider(null), 4, i -> row(
                "sessionId", i < 3 ? sA : sB, "sessionStartedAt", DAY.plusSeconds(i < 3 ? 0 : 3600),
                "store", "Akwa", "sessionStatus", "Validé", "productId", "p" + i, "product", "Savon " + i,
                "variant", i == 1 ? "Rouge" : null, "theoretical", counts[i][0],
                "physical", counts[i][1] < 0 ? null : counts[i][1],
                "gap", counts[i][1] < 0 ? null : counts[i][1] - counts[i][0], "countedAt", DAY, "countedBy", "Awa"));

        String all = render(provider, period(null)).all();

        assertThat(all).contains("Rapport d'inventaire", "Inventaire du 11/10/2026 10:00",
                "Inventaire du 11/10/2026 11:00", "Stock théorique", "Stock compté", "Écart", "Rouge",
                "-2", "+2", "—", "Total — 2 ligne(s) avec écart", "Total — 0 ligne(s) avec écart");
    }

    @Test
    @DisplayName("Fin de journée: one section per closure with the end-of-day builder's figures over ClosureWindow.forClosure")
    void endOfDay() throws IOException {
        UUID closureId = UUID.randomUUID();
        Instant closedAt = Instant.parse("2026-10-11T19:00:00Z"); // 20:00 WAT
        DayClosure closure = new DayClosure(closureId, STORE, UUID.randomUUID(), closedAt, null, false, TENANT,
                closedAt.plusSeconds(5));
        DayClosure previous = new DayClosure(UUID.randomUUID(), STORE, null, closedAt.minusSeconds(86_400), null,
                true, TENANT, closedAt.minusSeconds(86_000));
        when(closures.findById(closureId)).thenReturn(Optional.of(closure));
        when(closures.findPreviousForStore(STORE, closedAt)).thenReturn(Optional.of(previous));
        ClosureWindow expected = ClosureWindow.forClosure(closure, Optional.of(previous));
        when(reportBuilder.build(eq(STORE), eq(expected), eq("Boutique Akwa"), eq(LocalDate.of(2026, 10, 11)),
                eq(LocalTime.of(20, 0)), anyBoolean())).thenReturn(new EndOfDayReportData(
                "Boutique Akwa", LocalDate.of(2026, 10, 11), LocalTime.of(20, 0), false, false,
                12, 45_000, 30_000, 15_000, 3_750,
                List.of(new EndOfDayReportData.TopProductEntry("Savon Lux", 7, 7_000)),
                List.of(new EndOfDayReportData.EmployeeEntry("Awa Mbarga", 12, 45_000)),
                3, 2, 4_000, null));
        ListProvider provider = new ListProvider(new DayClosuresExportDatasetProvider(null), 1, i -> row(
                "id", closureId.toString(), "closureDate", LocalDate.of(2026, 10, 11), "closedAt", closedAt,
                "store", "Boutique Akwa", "automatic", false));

        Rendered pdf = render(provider, period(STORE));

        assertThat(pdf.all()).contains("Fin de journée", "Boutique Akwa", "Clôture du 11/10/2026 à 20:00",
                "Clôture manuelle", "Nombre de ventes", "12", "Chiffre d'affaires", "45 000 XAF", "Espèces",
                "30 000 XAF", "Mobile Money", "15 000 XAF", "Panier moyen", "3 750 XAF",
                "2 (4 000 XAF)", "Savon Lux", "7 000 XAF", "Awa Mbarga", "Page 1 / 1");
    }

    @Test
    @DisplayName("no rows → valid one-page PDF with the header and « Aucune donnée pour cette période »")
    void empty() throws IOException {
        for (ExportDatasetProvider p : List.of(sales(0),
                new ListProvider(new DayClosuresExportDatasetProvider(null), 0, i -> row()),
                new ListProvider(new StockLevelsExportDatasetProvider(null), 0, i -> row()),
                new ListProvider(new InventoryCountsExportDatasetProvider(null), 0, i -> row()))) {
            Rendered pdf = render(p, period(null));
            assertThat(pdf.pages()).hasSize(1);
            assertThat(pdf.rows()).isZero();
            assertThat(pdf.all()).contains("Aucune donnée pour cette période", "Généré le", "Page 1 / 1");
        }
    }

    @Test
    @DisplayName("rows beyond pdf-max-rows while rendering → not read past the limit, note « Document limité aux N premières lignes »")
    void truncatedAtLimit() throws IOException {
        props.setPdfMaxRows(5);
        props.setPageSize(3);
        ListProvider provider = sales(8);

        Rendered pdf = render(provider, period(null));

        assertThat(pdf.rows()).isEqualTo(5);
        assertThat(provider.rowsServed.get()).isEqualTo(6); // two pages of 3: the 6th row is never rendered
        assertThat(pdf.all()).contains("Document limité aux 5 premières lignes");
    }

    @Test
    @DisplayName("validate: another dataset or several datasets → 400 EXPORT_FORMAT_UNSUPPORTED (format=PDF, dataset)")
    void validateDatasets() {
        PdfExportRenderer r = renderer();
        ListProvider products = new ListProvider(new SalesExportDatasetProvider(null), 1, i -> row()) {
            @Override public String key() { return "products"; }
        };
        assertThatThrownBy(() -> r.validate(List.of(products)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException de = (DomainException) e;
                    assertThat(de.getDomainCode()).isEqualTo("EXPORT_FORMAT_UNSUPPORTED");
                    assertThat(de.getDetails()).containsEntry("format", "PDF").containsEntry("dataset", "products");
                });
        assertThatThrownBy(() -> r.validate(List.of(sales(1), sales(1))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("EXPORT_FORMAT_UNSUPPORTED");
        r.validate(List.of(sales(1)));
    }

    @Test
    @DisplayName("checkSize: rows > pdf-max-rows (closures > pdf-max-closures) → 422 EXPORT_PDF_TOO_LARGE with limit and rows")
    void checkSize() {
        PdfExportRenderer r = renderer();
        r.checkSize(List.of(sales(1)), ExportFilter.none(), 2000);
        assertThatThrownBy(() -> r.checkSize(List.of(sales(1)), ExportFilter.none(), 2001))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException de = (DomainException) e;
                    assertThat(de.getDomainCode()).isEqualTo("EXPORT_PDF_TOO_LARGE");
                    assertThat(de.getDetails()).containsEntry("limit", 2000).containsEntry("rows", 2001L);
                });
        ListProvider closuresProvider = new ListProvider(new DayClosuresExportDatasetProvider(null), 0, i -> row());
        r.checkSize(List.of(closuresProvider), ExportFilter.none(), 31);
        assertThatThrownBy(() -> r.checkSize(List.of(closuresProvider), ExportFilter.none(), 32))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDetails()).containsEntry("limit", 31));
    }

    @Test
    @DisplayName("amounts: integer XAF grouped with a non-breaking space")
    void amountFormat() {
        assertThat(PdfFormats.xaf(12_500)).isEqualTo("12 500 XAF");
        assertThat(PdfFormats.xaf(0)).isEqualTo("0 XAF");
        assertThat(PdfFormats.xaf(1_234_567)).isEqualTo("1 234 567 XAF");
        assertThat(PdfFormats.integer(-1500)).isEqualTo("-1 500");
        assertThat(Arrays.asList(PdfFormats.integer(999), PdfFormats.integer(1000))).containsExactly("999", "1 000");
    }

    @Test
    @DisplayName("bounded big volume: exactly pdf-max-rows (2000) sales rows → valid multi-page PDF, last page « Page N / N », grand total")
    void bigVolumeAtLimit() throws IOException {
        assertThat(props.getPdfMaxRows()).isEqualTo(2000);
        ListProvider provider = sales(2000);
        Rendered pdf = render(provider, period(null));

        assertThat(pdf.rows()).isEqualTo(2000);
        assertThat(provider.rowsServed.get()).isEqualTo(2000);
        int n = pdf.pages().size();
        assertThat(n).isGreaterThan(10);
        assertThat(pdf.pages().get(n - 1)).contains("Page " + n + " / " + n);
        // 1800 validated × 12 500 = 22 500 000 ; no truncation note
        assertThat(pdf.all()).contains("22 500 000 XAF").doesNotContain("Document limité");
    }

    @Test
    @DisplayName("État de stock: two stores sharing a name stay two groups with their own totals")
    void stockSameStoreName() throws IOException {
        String s1 = UUID.randomUUID().toString();
        String s2 = UUID.randomUUID().toString();
        // keyset orders by store NAME then product: rows of the two stores interleave
        ListProvider provider = new ListProvider(new StockLevelsExportDatasetProvider(null), 4, i -> row(
                "storeId", i % 2 == 0 ? s1 : s2, "store", "Akwa", "productId", "p" + i,
                "sku", "SKU-" + i, "product", "Produit " + i, "category", "", "quantity", 1L,
                "minimumThreshold", 0L, "stockStatus", "OK", "buyPrice", i % 2 == 0 ? 1000L : 3000L,
                "stockValue", i % 2 == 0 ? 1000L : 3000L, "productArchived", false, "updatedAt", DAY));

        String all = render(provider, ExportFilter.none()).all();

        assertThat(all.split("Valeur du stock — Akwa", -1)).hasSize(3); // two group totals
        assertThat(all).contains("2 000 XAF", "6 000 XAF", "8 000 XAF");
    }

    @Test
    @DisplayName("text with XML-illegal control characters renders (characters stripped)")
    void controlCharacters() throws IOException {
        ListProvider provider = new ListProvider(new SalesExportDatasetProvider(null), 1, i -> row(
                "id", "x", "occurredAt", DAY, "store", "Akwa", "employee", "Awa\u0001", "client", "Bad\u0007Client\u001F",
                "status", "Validée", "paymentMode", "Espèces", "itemCount", 1L, "discount", 0L, "total", 500L));

        assertThat(render(provider, period(null)).all()).contains("BadClient");
        assertThat(PdfFormats.text("a\u0000b\u000Bc\tok\n")).isEqualTo("abc\tok\n");
    }

    @Test
    @DisplayName("label constants match the providers' French labels (a rename must not empty totals / alerts)")
    void labelsMatchProviders() throws Exception {
        Class<?> saleLabels = Class.forName("com.keevo.commerce.sale.adapter.out.export.SaleLabels");
        java.lang.reflect.Field status = saleLabels.getDeclaredField("STATUS");
        status.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> labels = (Map<String, String>) status.get(null);
        assertThat(labels).containsEntry("COMPLETED", SalesPdfModel.VALIDATED)
                .containsEntry("CANCELLED", SalesPdfModel.CANCELLED);

        java.lang.reflect.Field ok = StockLevelsExportDatasetProvider.class.getDeclaredField("OK");
        ok.setAccessible(true);
        assertThat(ok.get(null)).isEqualTo(StockPdfModel.OK);
    }

    @Test
    @DisplayName("header: no business name → empty line, never « Keevo »")
    void headerWithoutBusinessName() {
        PdfHeader h = PdfHeader.of(PdfTemplate.SALES, null, null, ExportFilter.none(), false, NOW);
        assertThat(h.businessName()).isEmpty();
    }
}
