package com.keevo.reporting.export.integration;

import com.keevo.commerce.sale.domain.model.ClosureWindow;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.reporting.export.adapter.out.render.pdf.PdfFormats;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportRequest;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story v1s-18-3 — the « Fin de journée » PDF of a closure, exported through the real service on
 * PostgreSQL 16, shows the same figures as {@link EndOfDayReportBuilder#build} over
 * {@link ClosureWindow#forClosure} (the WhatsApp end-of-day report of that closure), including a
 * sale made offline before the previous closure and synced after it.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("« Fin de journée » PDF on PostgreSQL (v1s-18-3)")
class PdfEndOfDayPostgresIntegrationTest extends FullContextIntegrationTest {

    private static final ZoneId WAT = ZoneId.of("Africa/Douala");
    private static final Instant P_CLOSED = Instant.parse("2026-03-14T19:00:00Z");  // 20:00 WAT day 1
    private static final Instant C_CLOSED = Instant.parse("2026-03-15T19:00:00Z");  // 20:00 WAT day 2

    private static final UUID SELLER = UUID.randomUUID();

    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestExportUseCase requestExport;
    @Autowired EndOfDayReportBuilder reportBuilder;
    @Autowired DayClosureRepository closureRepository;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void closure(String q, UUID id, UUID store, Instant closedAt, Instant receivedAt) {
        jdbc.update("INSERT INTO " + q + "day_closures (id, store_id, closed_at, closure_date, is_automatic, "
                        + "received_at) VALUES (?, ?, ?, ?, FALSE, ?)",
                id, store, Timestamp.from(closedAt), Date.valueOf(LocalDate.ofInstant(closedAt, WAT)),
                Timestamp.from(receivedAt));
    }

    private void sale(String q, UUID store, int total, String mode, String status, Instant occurredAt,
                      Instant createdAt, String product, int qty) {
        UUID sale = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "sales (id, store_id, employee_id, total_amount, payment_mode, created_at, "
                        + "status, occurred_at, discount_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                sale, store, SELLER, total, mode, Timestamp.from(createdAt), status, Timestamp.from(occurredAt));
        jdbc.update("INSERT INTO " + q + "sale_items (id, sale_id, product_id, product_name, applied_unit_price, "
                        + "quantity, subtotal, catalogue_unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sale, UUID.randomUUID(), product, total / qty, qty, total, total / qty);
    }

    @Test
    @DisplayName("closure exported as « Fin de journée » PDF: count, revenue, cash, Mobile Money, pending, top product = EndOfDayReportBuilder over forClosure (late offline sale included)")
    void endOfDayPdfMatchesTheEndOfDayReport() throws Exception {
        String schema = tx.execute(status -> tenantFactory.create()).getSchemaName();
        String q = "\"" + schema + "\".";
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "stores (id, name) VALUES (?, 'Boutique Clôture')", store);
        UUID previousId = UUID.randomUUID();
        UUID closureId = UUID.randomUUID();
        closure(q, previousId, store, P_CLOSED, P_CLOSED.plusSeconds(5));
        closure(q, closureId, store, C_CLOSED, C_CLOSED.plusSeconds(5));

        Instant day2 = Instant.parse("2026-03-15T09:00:00Z");
        sale(q, store, 10_000, "CASH", "COMPLETED", day2, day2, "Savon Éclat", 4);
        sale(q, store, 5_000, "MOBILE_MONEY", "COMPLETED", day2.plusSeconds(600), day2.plusSeconds(600), "Huile", 1);
        // made offline before the previous closure, received after it → belongs to C
        sale(q, store, 3_000, "CASH", "COMPLETED", Instant.parse("2026-03-14T15:00:00Z"),
                Instant.parse("2026-03-15T08:00:00Z"), "Huile", 1);
        sale(q, store, 2_000, "CASH", "PENDING_VALIDATION", day2.plusSeconds(1200), day2.plusSeconds(1200), "Huile", 1);
        // covered by the previous closure / after this closure: not in C
        sale(q, store, 7_000, "CASH", "COMPLETED", Instant.parse("2026-03-14T12:00:00Z"),
                Instant.parse("2026-03-14T12:00:00Z"), "Riz", 1);
        sale(q, store, 9_000, "MOBILE_MONEY", "COMPLETED", C_CLOSED.plusSeconds(3600), C_CLOSED.plusSeconds(3600),
                "Riz", 1);

        // the closure's day in WAT, for that store
        ExportFilter filter = new ExportFilter(store, LocalDate.of(2026, 3, 15).atStartOfDay(WAT).toInstant(),
                LocalDate.of(2026, 3, 16).atStartOfDay(WAT).toInstant().minusSeconds(1));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EndOfDayReportData expected;
        TenantContext.setCurrentTenant(schema);
        try {
            var outcome = requestExport.request(schema, UUID.randomUUID(),
                    new ExportRequest(java.util.List.of("day_closures"), ExportFormat.PDF, filter), (ct, name) -> {
                        assertThat(ct).isEqualTo("application/pdf");
                        assertThat(name).endsWith(".pdf");
                        return out;
                    });
            assertThat(outcome).isInstanceOf(RequestExportUseCase.Direct.class);
            assertThat(((RequestExportUseCase.Direct) outcome).rowCount()).isEqualTo(1);

            DayClosure c = closureRepository.findById(closureId).orElseThrow();
            Optional<DayClosure> previous = closureRepository.findPreviousForStore(store, c.getClosedAt());
            assertThat(previous).map(DayClosure::getId).contains(previousId);
            expected = reportBuilder.build(store, ClosureWindow.forClosure(c, previous), "Boutique Clôture",
                    LocalDate.of(2026, 3, 15), c.getClosedAt().atZone(WAT).toLocalTime(), false);
        } finally {
            TenantContext.clear();
        }

        // sanity: the late offline sale is in, the previous closure's and the next day's are out
        assertThat(expected.totalSales()).isEqualTo(3);
        assertThat(expected.totalRevenue()).isEqualTo(18_000);
        assertThat(expected.cashAmount()).isEqualTo(13_000);
        assertThat(expected.momoAmount()).isEqualTo(5_000);
        assertThat(expected.pendingSalesCount()).isEqualTo(1);

        String text;
        try (PDDocument doc = Loader.loadPDF(out.toByteArray())) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("Fin de journée", "Boutique Clôture", "Clôture du 15/03/2026 à 20:00",
                "Nombre de ventes", PdfFormats.integer(expected.totalSales()),
                PdfFormats.xaf(expected.totalRevenue()),
                PdfFormats.xaf(expected.cashAmount()),
                PdfFormats.xaf(expected.momoAmount()),
                PdfFormats.integer(expected.pendingSalesCount()) + " (" + PdfFormats.xaf(expected.pendingSalesTotal()) + ")",
                expected.topProducts().get(0).name());
        assertThat(expected.topProducts().get(0).name()).isEqualTo("Savon Éclat");
        // one closure only: the previous day's closure is outside the period
        assertThat(text.split("Clôture du ", -1)).hasSize(2);
    }
}
