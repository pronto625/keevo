package com.keevo.admin.catalog.adapter.in.rest;

import com.keevo.admin.AdminLabels;
import com.keevo.admin.catalog.domain.model.AdminProductListItem;

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

    private static final Map<String, String> PLAN_LABELS = Map.of(
            "FREE", "Gratuit",
            "PAID", "Premium et essai",
            "PREMIUM_TRIAL", "Essai Premium",
            "PREMIUM", "Premium");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "ACTIVE", "Actif",
            "DRAFT", "Brouillon",
            "ARCHIVED", "Archivé");

    private ProductsCsvWriter() {}

    static byte[] write(List<AdminProductListItem> products) {
        StringBuilder csv = new StringBuilder(BOM).append(HEADER).append("\r\n");
        for (AdminProductListItem item : products) {
            String tenant = item.tenantName() == null || item.tenantName().isBlank()
                    ? AdminLabels.UNNAMED_TENANT : item.tenantName();
            csv.append(escape(item.name())).append(';')
               .append(escape(tenant)).append(';')
               .append(label(PLAN_LABELS, item.tenantPlan())).append(';')
               .append(escape(item.categoryName())).append(';')
               .append(item.price()).append(';')
               .append(item.stockQuantity()).append(';')
               .append(label(STATUS_LABELS, item.status())).append(';')
               .append(item.updatedAt() != null ? FR_DATE.format(item.updatedAt()) : "")
               .append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
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
