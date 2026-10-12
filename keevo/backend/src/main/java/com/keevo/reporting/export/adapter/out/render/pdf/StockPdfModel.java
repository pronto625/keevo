package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model of the « État de stock » PDF (Story v1s-18-3) from the {@code stock_levels} dataset, read
 * in its keyset order (store, product): one group per store with its stock value, plus the total.
 */
public final class StockPdfModel {

    static final String OK = "OK";

    public record StockLine(String sku, String product, String category, String quantity, String threshold,
                            String status, String buyPrice, String value, boolean alert) {
    }

    public record StoreGroup(String name, List<StockLine> lines, String totalValue) {
    }

    private StockPdfModel() {
    }

    static Map<String, Object> build(ExportDatasetProvider provider, ExportFilter filter, PdfHeader header,
                                     int pageSize, int limit, AtomicLong progress) {
        // Grouped by store id (the keyset orders by store NAME: two stores may share a name).
        Map<String, String> names = new java.util.LinkedHashMap<>();
        Map<String, List<StockLine>> lines = new java.util.HashMap<>();
        Map<String, long[]> values = new java.util.HashMap<>();
        long[] grand = new long[1];
        PdfRowReader.Result result = PdfRowReader.read(provider, filter, pageSize, limit, progress, row -> {
            String storeId = row.text("storeId");
            names.putIfAbsent(storeId, row.text("store").isEmpty() ? "Boutique" : row.text("store"));
            long value = row.longOrZero("stockValue");
            Long quantity = row.longValue("quantity");
            Long threshold = row.longValue("minimumThreshold");
            Long buyPrice = row.longValue("buyPrice");
            Object archived = row.get("productArchived");
            String product = row.text("product") + (Boolean.TRUE.equals(archived) ? " (archivé)" : "");
            String status = row.text("stockStatus");
            lines.computeIfAbsent(storeId, k -> new ArrayList<>()).add(new StockLine(row.text("sku"), product,
                    row.text("category"),
                    quantity == null ? "" : PdfFormats.integer(quantity),
                    threshold == null ? "" : PdfFormats.integer(threshold), status,
                    buyPrice == null ? "" : PdfFormats.xaf(buyPrice), PdfFormats.xaf(value),
                    !status.isEmpty() && !OK.equals(status)));
            values.computeIfAbsent(storeId, k -> new long[1])[0] += value;
            grand[0] += value;
        });
        List<StoreGroup> groups = new ArrayList<>();
        names.forEach((id, name) -> groups.add(new StoreGroup(name, lines.get(id), PdfFormats.xaf(values.get(id)[0]))));
        Map<String, Object> vars = PdfModels.base(header, groups.isEmpty(), result.truncated(), limit, "lignes");
        vars.put("stores", groups);
        vars.put("grandTotal", PdfFormats.xaf(grand[0]));
        return vars;
    }
}
