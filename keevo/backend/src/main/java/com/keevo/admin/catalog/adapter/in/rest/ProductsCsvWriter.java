package com.keevo.admin.catalog.adapter.in.rest;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;

import com.keevo.identity.api.PlanKind;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Catalog CSV (Story 9-6, fixed by spec 9-1 completion): UTF-8 with BOM (Excel), {@code ;}
 * separator, French headers, plan / status as French labels, dates dd/MM/yyyy in Africa/Douala,
 * no internal id — same conventions as the 9-3 churn export.
 */
final class ProductsCsvWriter {

    static final String HEADER =
            "Produit;Entreprise;Plan;Catégorie;Prix (XAF);Stock;Statut;Dernière mise à jour";
    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    private static final String BOM = "﻿";
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(WAT);

    /** Plan class of the product list (Story 19.7a: by kind — free, or paying = trial and paid plans). */
    private static final Map<String, String> PLAN_LABELS = Map.of(
            PlanKind.FREE.name(), "Gratuit",
            "PAID", "Premium et essai");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "ACTIVE", "Actif",
            "DRAFT", "Brouillon",
            "ARCHIVED", "Archivé");

    private ProductsCsvWriter() {}

    /** Story 19.11c — the BOM and the header line (streamed export). */
    static void writeHeader(Appendable out) {
        append(out, BOM + HEADER + "\r\n");
    }

    /** Story 19.11c — one product line (streamed export). */
    static void writeRow(Appendable out, AdminProductListItem item) {
        String tenant = item.tenantName() == null || item.tenantName().isBlank()
                ? AdminLabels.UNNAMED_TENANT : item.tenantName();
        append(out, escape(item.name()) + ';'
                + escape(tenant) + ';'
                + label(PLAN_LABELS, item.tenantPlan()) + ';'
                + escape(item.categoryName()) + ';'
                + item.price() + ';'
                + item.stockQuantity() + ';'
                + label(STATUS_LABELS, item.status()) + ';'
                + (item.updatedAt() != null ? FR_DATE.format(item.updatedAt()) : "")
                + "\r\n");
    }

    /** Story 19.11c — last line of an export cut by an error after the first rows were sent. */
    static final String INTERRUPTED = "Export interrompu : fichier incomplet, relancez l'export";

    /** Marks a streamed export cut short (best effort: the client may be gone). */
    static void writeInterrupted(Appendable out) {
        try {
            out.append(INTERRUPTED).append("\r\n");
        } catch (java.io.IOException ignored) {
            // client gone: nothing more can be written
        }
    }

    private static void append(Appendable out, String text) {
        try {
            out.append(text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e); // client gone mid-download: the request ends
        }
    }

    /** {@code keevo-products-YYYY-MM-DD.csv}, the day taken in Africa/Douala. */
    static String filename(LocalDate today) {
        return "keevo-products-" + today + ".csv";
    }

    static LocalDate today() {
        return LocalDate.now(WAT);
    }

    private static String label(Map<String, String> labels, String code) {
        if (code == null || code.isBlank()) return "";
        return labels.getOrDefault(code.trim().toUpperCase(java.util.Locale.ROOT), "Autre");
    }

    /** Quotes values containing the separator, quotes or line breaks; neutralises spreadsheet formulas. */
    static String escape(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
