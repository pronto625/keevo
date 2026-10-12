package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.application.port.export.ExportFilter;

import java.time.Instant;

/**
 * Brand header repeated on every page of a PDF (Story v1s-18-3): document title, business name,
 * store (or « Toutes les boutiques »), period (or « Situation au … » for a snapshot) and the
 * generation time in WAT. No logo: the store / tenant model has none (recorded decision).
 */
public record PdfHeader(String title, String businessName, String scope, String period, String generatedAt) {

    static final String ALL_STORES = "Toutes les boutiques";

    /**
     * @param storeName name of the filtered store, {@code null} for all stores
     * @param snapshot  the document shows the current state (stock): « Situation au … »
     */
    static PdfHeader of(PdfTemplate template, String businessName, String storeName, ExportFilter filter,
                        boolean snapshot, Instant now) {
        String period = snapshot
                ? "Situation au " + PdfFormats.date(now) + " à " + PdfFormats.time(now)
                : period(filter);
        return new PdfHeader(template.label(),
                businessName == null || businessName.isBlank() ? "" : businessName,
                storeName != null ? storeName : ALL_STORES,
                period,
                "Généré le " + PdfFormats.date(now) + " à " + PdfFormats.time(now));
    }

    static String period(ExportFilter filter) {
        if (filter.from() != null && filter.to() != null) {
            String from = PdfFormats.date(filter.from());
            String to = PdfFormats.date(filter.to());
            return from.equals(to) ? "Journée du " + from : "Du " + from + " au " + to;
        }
        if (filter.from() != null) return "Depuis le " + PdfFormats.date(filter.from());
        if (filter.to() != null) return "Jusqu'au " + PdfFormats.date(filter.to());
        return "Toutes les dates";
    }
}
