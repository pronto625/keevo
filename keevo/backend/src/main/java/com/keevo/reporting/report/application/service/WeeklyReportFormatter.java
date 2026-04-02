package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * WeeklyReportFormatter — formats WeeklyReportData into an emoji-rich French WhatsApp message.
 * Story 7.3 — Rapport Hebdomadaire Automatique (AC3).
 *
 * <p>Pure formatter — no Spring context, no persistence. Easy to unit-test.
 * Day abbreviations: lun/mar/mer/jeu/ven/sam/dim (French locale).
 */
@Component
public class WeeklyReportFormatter {

    private static final DateTimeFormatter DAY_MON_YEAR =
            DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter DAY_MON =
            DateTimeFormatter.ofPattern("EEE dd MMM", Locale.FRENCH);

    /**
     * Format a WeeklyReportData into a WhatsApp-friendly French message.
     */
    public String format(WeeklyReportData data) {
        StringBuilder sb = new StringBuilder();

        // ── Header ──────────────────────────────────────────────────────────
        sb.append("📅 Rapport Hebdomadaire — ").append(data.storeName()).append("\n");
        sb.append("📆 Semaine du ").append(data.weekStart().format(DAY_MON))
          .append(" au ").append(data.weekEnd().format(DAY_MON_YEAR)).append("\n");
        sb.append("\n");

        // ── Zero sales shortcut ─────────────────────────────────────────────
        if (data.totalSales() == 0) {
            sb.append("Aucune vente enregistrée cette semaine.\n");
            appendAutoNote(sb, data.isAutomatic());
            return sb.toString();
        }

        // ── Revenue summary ──────────────────────────────────────────────────
        String arrow = resolveArrow(data);
        String deltaStr = resolveDeltaStr(data);
        sb.append("💰 CA Semaine : ").append(data.totalRevenue()).append(" FCFA ")
          .append(arrow).append(" vs semaine précédente (").append(deltaStr).append(")\n");
        sb.append("🛍 Ventes : ").append(data.totalSales())
          .append(" | 🧺 Panier moyen : ").append(data.avgBasket()).append(" FCFA\n");
        sb.append("💵 Cash : ").append(data.cashAmount())
          .append(" FCFA | 📱 MoMo : ").append(data.momoAmount()).append(" FCFA\n");
        sb.append("\n");

        // ── Top 5 by revenue ─────────────────────────────────────────────────
        sb.append("🏆 Top 5 produits (revenus) :\n");
        appendTopProducts(sb, data.topProductsByRevenue(), false);
        sb.append("\n");

        // ── Top 5 by qty ────────────────────────────────────────────────────
        sb.append("📦 Top 5 produits (quantités) :\n");
        appendTopProducts(sb, data.topProductsByQty(), true);
        sb.append("\n");

        // ── Employee breakdown ───────────────────────────────────────────────
        if (!data.employeeBreakdown().isEmpty()) {
            sb.append("👤 Équipe de la semaine :\n");
            for (EmployeeEntry emp : data.employeeBreakdown()) {
                sb.append("• ").append(emp.name())
                  .append(" : ").append(emp.salesCount()).append(" ventes")
                  .append(" — ").append(emp.revenue()).append(" FCFA\n");
            }
            sb.append("\n");
        }

        // ── Low stock alert ──────────────────────────────────────────────────
        if (data.lowStockCount() == 0) {
            sb.append("📦 Stock : Aucune alerte\n");
        } else {
            sb.append("📦 Alertes stock : ").append(data.lowStockCount())
              .append(" produit(s) en rupture\n");
        }

        appendAutoNote(sb, data.isAutomatic());
        return sb.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveArrow(WeeklyReportData data) {
        if (data.previousWeekRevenue() == 0) return "→";
        if (data.weekOverWeekDelta() > 0)    return "↑";
        if (data.weekOverWeekDelta() < 0)    return "↓";
        return "→";
    }

    private String resolveDeltaStr(WeeklyReportData data) {
        if (data.previousWeekRevenue() == 0) {
            return "données insuffisantes";
        }
        int delta = data.weekOverWeekDelta();
        if (delta > 0) return "+" + delta + " FCFA";
        if (delta < 0) return "-" + Math.abs(delta) + " FCFA";
        return "+0 FCFA";
    }

    private void appendTopProducts(StringBuilder sb, List<TopProductEntry> products, boolean byQty) {
        int rank = 1;
        for (TopProductEntry p : products) {
            sb.append(rank++).append(". ").append(p.name()).append(" — ");
            if (byQty) {
                sb.append(p.qty()).append(" vendu(s)");
            } else {
                sb.append(p.revenue()).append(" FCFA");
            }
            sb.append("\n");
        }
    }

    private void appendAutoNote(StringBuilder sb, boolean isAutomatic) {
        if (isAutomatic) {
            sb.append("⏰ Rapport auto-généré\n");
        }
    }
}
