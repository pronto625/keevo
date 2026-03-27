package com.keevo.inventory.counting.domain.service;

import com.keevo.inventory.counting.domain.model.InventoryGapReport;
import com.keevo.inventory.counting.domain.model.InventoryGapRow;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * InventoryReportTextFormatter — GoF Strategy for generating text reports.
 *
 * <p>Generates emoji-rich WhatsApp text and detailed export text,
 * matching the day-close report style (Story 4.4).
 * Story 6.3 — Gap Analysis Report.
 */
@Component
public class InventoryReportTextFormatter {

    private static final ZoneId WAT_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter FR_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH'h'mm");

    /**
     * Generates emoji-rich compact WhatsApp text.
     * Max 5 "Top manques" entries to keep message compact.
     */
    public String formatWhatsApp(InventoryGapReport report, String actorName) {
        StringBuilder sb = new StringBuilder();
        ZonedDateTime zdt = report.getGeneratedAt().atZone(WAT_ZONE);
        String date = zdt.format(FR_DATE);
        String time = zdt.format(TIME_FORMAT);

        sb.append("📋 Rapport d'inventaire — ").append(report.getStoreName()).append("\n");
        sb.append("📅 ").append(date).append(" — ").append(time).append("\n");
        sb.append("👤 ").append(actorName).append("\n\n");

        sb.append("✅ Concordants : ").append(report.getSummary().totalConcordant())
                .append(" produit").append(report.getSummary().totalConcordant() > 1 ? "s" : "").append("\n");

        if (report.getSummary().totalSurplus() > 0) {
            sb.append("⚠️ Surplus : ").append(report.getSummary().totalSurplus())
                    .append(" produit").append(report.getSummary().totalSurplus() > 1 ? "s" : "")
                    .append(" (+").append(formatXaf(report.getSummary().totalSurplusValueXaf()))
                    .append(")\n");
        }

        if (report.getSummary().totalShortage() > 0) {
            sb.append("🔴 Manquants : ").append(report.getSummary().totalShortage())
                    .append(" produit").append(report.getSummary().totalShortage() > 1 ? "s" : "")
                    .append(" (−").append(formatXaf(report.getSummary().totalShortageValueXaf()))
                    .append(")\n");
        }

        if (!report.getShortageRows().isEmpty()) {
            sb.append("\nTop manques :\n");
            report.getShortageRows().stream().limit(5).forEach(row -> {
                sb.append("• ").append(row.productName());
                if (row.variantLabel() != null) sb.append(" ").append(row.variantLabel());
                sb.append(" : ").append(row.ecart()).append(" unité")
                        .append(Math.abs(row.ecart()) > 1 ? "s" : "")
                        .append(" (−").append(formatXaf(row.gapValueXaf())).append(")\n");
            });
        }

        return sb.toString();
    }

    /**
     * Generates detailed text export (for "Télécharger" on Free plan).
     */
    public String formatDetailedText(InventoryGapReport report, String actorName) {
        StringBuilder sb = new StringBuilder();
        ZonedDateTime zdt = report.getGeneratedAt().atZone(WAT_ZONE);
        String date = zdt.format(FR_DATE);
        String time = zdt.format(TIME_FORMAT);

        sb.append("Rapport d'inventaire — ").append(report.getStoreName()).append("\n");
        sb.append("Date : ").append(date).append(" — ").append(time).append("\n");
        sb.append("Réalisé par : ").append(actorName).append("\n\n");

        sb.append("Résumé :\n");
        sb.append("✅ Concordants : ").append(report.getSummary().totalConcordant()).append(" produits\n");

        if (report.getSummary().totalSurplus() > 0) {
            sb.append("⚠️ Surplus : ").append(report.getSummary().totalSurplus())
                    .append(" produits (+").append(formatXaf(report.getSummary().totalSurplusValueXaf()))
                    .append(")\n");
        }
        if (report.getSummary().totalShortage() > 0) {
            sb.append("🔴 Manquants : ").append(report.getSummary().totalShortage())
                    .append(" produits (−").append(formatXaf(report.getSummary().totalShortageValueXaf()))
                    .append(")\n");
        }

        if (!report.getShortageRows().isEmpty()) {
            sb.append("\nDétail des manquants :\n");
            for (InventoryGapRow row : report.getShortageRows()) {
                sb.append("• ").append(row.productName());
                if (row.variantLabel() != null) sb.append(" ").append(row.variantLabel());
                sb.append(" : Keevo ").append(row.theoretical())
                        .append(" → Réel ").append(row.physical())
                        .append(" (").append(row.ecart()).append(")")
                        .append(" — −").append(formatXaf(row.gapValueXaf())).append("\n");
            }
        }

        if (!report.getSurplusRows().isEmpty()) {
            sb.append("\nDétail des surplus :\n");
            for (InventoryGapRow row : report.getSurplusRows()) {
                sb.append("• ").append(row.productName());
                if (row.variantLabel() != null) sb.append(" ").append(row.variantLabel());
                sb.append(" : Keevo ").append(row.theoretical())
                        .append(" → Réel ").append(row.physical())
                        .append(" (+").append(row.ecart()).append(")")
                        .append(" — +").append(formatXaf(row.gapValueXaf())).append("\n");
            }
        }

        return sb.toString();
    }

    String formatXaf(long amount) {
        String str = String.valueOf(amount);
        StringBuilder buf = new StringBuilder();
        int len = str.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) buf.append(' ');
            buf.append(str.charAt(i));
        }
        return buf + " FCFA";
    }
}
