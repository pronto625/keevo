package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.infrastructure.pdf.PdfFormats;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model of the « Liste des ventes » PDF (Story v1s-18-3) from the {@code sales} dataset rows: one
 * line per sale, totals by status, validated sales by payment mode and their grand total.
 */
public final class SalesPdfModel {

    /** Status label of a validated sale in the {@code sales} dataset. */
    static final String VALIDATED = "Validée";
    static final String CANCELLED = "Annulée";

    public record SaleLine(String date, String store, String employee, String client, String status,
                           String paymentMode, String items, String amount, boolean cancelled) {
    }

    public record TotalLine(String label, String count, String amount) {
    }

    private SalesPdfModel() {
    }

    static Map<String, Object> build(ExportDatasetProvider provider, ExportFilter filter, PdfHeader header,
                                     int pageSize, int limit, AtomicLong progress) {
        List<SaleLine> sales = new ArrayList<>();
        Map<String, long[]> byStatus = new LinkedHashMap<>();
        Map<String, long[]> byPayment = new LinkedHashMap<>();
        long[] grand = new long[2];
        PdfRowReader.Result result = PdfRowReader.read(provider, filter, pageSize, limit, progress, row -> {
            String status = row.text("status");
            String payment = row.text("paymentMode");
            long amount = row.longOrZero("total");
            Long items = row.longValue("itemCount");
            sales.add(new SaleLine(PdfFormats.dateTime(row.instant("occurredAt")), row.text("store"),
                    row.text("employee"), row.text("client"), status, payment,
                    items == null ? "" : PdfFormats.integer(items), PdfFormats.xaf(amount), CANCELLED.equals(status)));
            add(byStatus, status.isEmpty() ? "—" : status, amount);
            if (VALIDATED.equals(status)) {
                add(byPayment, payment.isEmpty() ? "—" : payment, amount);
                grand[0]++;
                grand[1] += amount;
            }
        });
        Map<String, Object> vars = PdfModels.base(header, sales.isEmpty(), result.truncated(), limit, "lignes");
        vars.put("sales", sales);
        vars.put("showStore", filter.storeId() == null);
        vars.put("byStatus", totals(byStatus));
        vars.put("byPayment", totals(byPayment));
        vars.put("grandCount", PdfFormats.integer(grand[0]));
        vars.put("grandTotal", PdfFormats.xaf(grand[1]));
        return vars;
    }

    private static void add(Map<String, long[]> totals, String key, long amount) {
        long[] t = totals.computeIfAbsent(key, k -> new long[2]);
        t[0]++;
        t[1] += amount;
    }

    private static List<TotalLine> totals(Map<String, long[]> totals) {
        return totals.entrySet().stream()
                .map(e -> new TotalLine(e.getKey(), PdfFormats.integer(e.getValue()[0]), PdfFormats.xaf(e.getValue()[1])))
                .toList();
    }
}
