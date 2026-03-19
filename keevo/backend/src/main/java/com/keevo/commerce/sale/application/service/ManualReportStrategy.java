package com.keevo.commerce.sale.application.service;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * ManualReportStrategy — WhatsApp report for manual day closure.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 */
public class ManualReportStrategy implements ClosureReportStrategy {

    private static final ZoneId WAT_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH'h'mm");

    @Override
    public String buildReport(DayClosedEvent event, String storeName, String employeeName) {
        DayClosureSummary summary = event.summary();
        String date = event.occurredAt().atZone(WAT_ZONE).format(FR_DATE);
        String time = event.occurredAt().atZone(WAT_ZONE).format(TIME_FORMAT);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 Clôture ").append(storeName).append(" — ").append(date).append("\n");
        sb.append("👤 Vendeur : ").append(employeeName).append("\n");
        sb.append("💰 CA : ").append(summary.totalRevenue()).append(" FCFA\n");
        sb.append("🛍 Ventes : ").append(summary.totalSales()).append("\n");

        if (summary.topProductName() != null) {
            sb.append("📦 Top produit : ").append(summary.topProductName())
                    .append(" (×").append(summary.topProductQty()).append(")\n");
        }

        sb.append("💵 Cash : ").append(summary.cashAmount())
                .append(" | 📱 MoMo : ").append(summary.momoAmount()).append("\n");

        sb.append("✅ Clôture manuelle à ").append(time).append("\n");

        // Pending sales line (only if > 0)
        if (summary.pendingSalesCount() > 0) {
            sb.append("🔶 En attente : ").append(summary.pendingSalesCount())
                    .append(" vente(s) — ").append(summary.pendingSalesTotal())
                    .append(" FCFA (non comptabilisé)\n");
        }

        return sb.toString();
    }
}
