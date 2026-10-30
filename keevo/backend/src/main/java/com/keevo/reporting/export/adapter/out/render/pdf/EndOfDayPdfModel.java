package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.infrastructure.pdf.PdfFormats;
import com.keevo.commerce.api.ClosureWindow;
import com.keevo.commerce.api.DayClosureQueryPort;
import com.keevo.commerce.api.DayClosureView;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model of the « Fin de journée » PDF (Story v1s-18-3): one section per closure of the
 * {@code day_closures} rows matching the filter. Each section's figures come from
 * {@link EndOfDayReportBuilder#build(UUID, ClosureWindow, String, java.time.LocalDate, java.time.LocalTime, boolean)}
 * over {@link ClosureWindow#forClosure(DayClosureView, Optional)} — the window the closure was made
 * with, late offline sales included — so the PDF equals the WhatsApp end-of-day report.
 */
@Component
public class EndOfDayPdfModel {

    public record TopLine(String name, String quantity, String revenue) {
    }

    public record EmployeeLine(String name, String salesCount, String revenue) {
    }

    public record ClosureSection(String title, String subtitle, String salesCount, String revenue, String cash,
                                 String momo, String avgBasket, String pending, String lowStock,
                                 List<TopLine> topProducts, List<EmployeeLine> employees) {
    }

    private final DayClosureQueryPort closureQueryPort;
    private final EndOfDayReportBuilder reportBuilder;

    public EndOfDayPdfModel(DayClosureQueryPort closureQueryPort, EndOfDayReportBuilder reportBuilder) {
        this.closureQueryPort = closureQueryPort;
        this.reportBuilder = reportBuilder;
    }

    Map<String, Object> build(ExportDatasetProvider provider, ExportFilter filter, PdfHeader header,
                              int pageSize, int maxClosures, AtomicLong progress) {
        // Rows first (at most maxClosures ids + names), then one report per closure.
        List<String[]> rows = new ArrayList<>();
        PdfRowReader.Result result = PdfRowReader.read(provider, filter, pageSize, maxClosures, progress,
                row -> rows.add(new String[]{row.text("id"), row.text("store")}));
        List<ClosureSection> sections = new ArrayList<>();
        for (String[] r : rows) {
            closureQueryPort.findById(TenantContext.getCurrentTenant(), UUID.fromString(r[0]))
                    .map(c -> section(c, r[1]))
                    .ifPresent(sections::add);
        }
        Map<String, Object> vars = PdfModels.base(header, sections.isEmpty(), result.truncated(), maxClosures,
                "clôtures");
        vars.put("closures", sections);
        return vars;
    }

    /** Figures of one closure, exactly as its end-of-day report. */
    EndOfDayReportData report(DayClosureView closure, String storeName) {
        Optional<DayClosureView> previous = closureQueryPort.findPreviousForStore(TenantContext.getCurrentTenant(), closure.storeId(),
                closure.closedAt());
        ClosureWindow window = ClosureWindow.forClosure(closure, previous);
        Instant closedAt = closure.closedAt();
        return reportBuilder.build(closure.storeId(), window, storeName,
                closedAt.atZone(PdfFormats.WAT).toLocalDate(), closedAt.atZone(PdfFormats.WAT).toLocalTime(),
                closure.automatic());
    }

    private ClosureSection section(DayClosureView closure, String storeName) {
        EndOfDayReportData d = report(closure, storeName);
        Instant closedAt = closure.closedAt();
        String subtitle = (storeName == null || storeName.isEmpty() ? "Boutique" : storeName)
                + " · " + (closure.automatic() ? "Clôture automatique" : "Clôture manuelle");
        return new ClosureSection(
                "Clôture du " + PdfFormats.date(closedAt) + " à " + PdfFormats.time(closedAt),
                subtitle,
                PdfFormats.integer(d.totalSales()),
                PdfFormats.xaf(d.totalRevenue()),
                PdfFormats.xaf(d.cashAmount()),
                PdfFormats.xaf(d.momoAmount()),
                PdfFormats.xaf(d.avgBasket()),
                PdfFormats.integer(d.pendingSalesCount()) + " (" + PdfFormats.xaf(d.pendingSalesTotal()) + ")",
                PdfFormats.integer(d.lowStockCount()),
                d.topProducts().stream()
                        .map(t -> new TopLine(t.name(), PdfFormats.integer(t.qty()), PdfFormats.xaf(t.revenue())))
                        .toList(),
                d.employeeBreakdown().stream()
                        .map(e -> new EmployeeLine(e.name(), PdfFormats.integer(e.salesCount()),
                                PdfFormats.xaf(e.revenue())))
                        .toList());
    }
}
