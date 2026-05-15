package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * DailyReportFormatter — formats EndOfDayReportData into the WhatsApp text template.
 * Story 7.2 — Task 6.2 (GREEN).
 *
 * <p>Pure function — no side effects. Fully unit-testable.
 * Implements the emoji-rich French format from AC3.
 */
@Component
public class DailyReportFormatter {

    private static final Locale FR = Locale.FRENCH;
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", FR);
    private static final DateTimeFormatter FR_TIME = DateTimeFormatter.ofPattern("HH'h'mm");

    /**
     * Format the report data into a WhatsApp-ready string.
     */
    public String format(EndOfDayReportData data) {
        String closureType = data.isAutomatic() ? "Auto" : "Manuel";
        String formattedDate = data.reportDate().format(FR_DATE);
        String formattedTime = data.closeTime().format(FR_TIME);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 Rapport global — ").append(data.storeName()).append("\n");
        sb.append("📅 ").append(formattedDate)
          .append(" | ⏰ ").append(formattedTime)
          .append(" (").append(closureType).append(")").append("\n\n");

        if (data.totalSales() == 0) {
            sb.append("Aucune vente enregistrée aujourd'hui.");
            return sb.toString();
        }

        // Revenue section
        sb.append("💰 CA Total : ").append(formatXAF(data.totalRevenue())).append(" FCFA\n");
        sb.append("🛍 Ventes : ").append(data.totalSales())
          .append(" | 🧺 Panier moyen : ").append(formatXAF(data.avgBasket())).append(" FCFA\n");
        sb.append("💵 Cash : ").append(formatXAF(data.cashAmount())).append(" FCFA")
          .append(" | 📱 MoMo : ").append(formatXAF(data.momoAmount())).append(" FCFA\n");

        // Top products
        List<TopProductEntry> top = data.topProducts();
        if (!top.isEmpty()) {
            sb.append("\n🏆 Top produits :\n");
            for (int i = 0; i < top.size(); i++) {
                TopProductEntry p = top.get(i);
                sb.append(i + 1).append(". ")
                  .append(p.name()).append(" — ")
                  .append(p.qty()).append(" vendu(s) — ")
                  .append(formatXAF(p.revenue())).append(" FCFA\n");
            }
        }

        // Employee breakdown
        List<EmployeeEntry> employees = data.employeeBreakdown();
        if (!employees.isEmpty()) {
            sb.append("\n👤 Équipe :\n");
            for (EmployeeEntry emp : employees) {
                sb.append("• ").append(emp.name())
                  .append(" : ").append(emp.salesCount()).append(" ventes — ")
                  .append(formatXAF(emp.revenue())).append(" FCFA\n");
            }
        }

        // Stock alerts
        sb.append("\n📦 Alertes stock : ").append(data.lowStockCount())
          .append(" produit(s) en rupture");

        // Pending sales (only if any)
        if (data.pendingSalesCount() > 0) {
            sb.append("\n⏳ Ventes en attente : ").append(data.pendingSalesCount())
              .append(" (").append(formatXAF(data.pendingSalesTotal())).append(" FCFA)");
        }

        return sb.toString();
    }

    /**
     * Format a personal employee report (no team section, personalized header).
     */
    public String formatEmployee(EndOfDayReportData data) {
        String closureType = data.isAutomatic() ? "Auto" : "Manuel";
        String formattedDate = data.reportDate().format(FR_DATE);
        String formattedTime = data.closeTime().format(FR_TIME);

        StringBuilder sb = new StringBuilder();
        String employeePart = (data.employeeName() != null && !data.employeeName().isBlank())
                ? " | " + data.employeeName()
                : "";
        sb.append("📊 Rapport du jour — ").append(data.storeName()).append(employeePart).append("\n");
        sb.append("📅 ").append(formattedDate)
          .append(" | ⏰ ").append(formattedTime)
          .append(" (").append(closureType).append(")").append("\n\n");

        if (data.totalSales() == 0) {
            sb.append("Aucune vente enregistrée.");
            return sb.toString();
        }

        sb.append("💰 Votre CA : ").append(formatXAF(data.totalRevenue())).append(" FCFA\n");
        sb.append("🛍 Ventes : ").append(data.totalSales())
          .append(" | 🧺 Panier moyen : ").append(formatXAF(data.avgBasket())).append(" FCFA\n");
        sb.append("💵 Cash : ").append(formatXAF(data.cashAmount())).append(" FCFA")
          .append(" | 📱 MoMo : ").append(formatXAF(data.momoAmount())).append(" FCFA\n");

        List<TopProductEntry> top = data.topProducts();
        if (!top.isEmpty()) {
            sb.append("\n🏆 Vos top produits :\n");
            for (int i = 0; i < top.size(); i++) {
                TopProductEntry p = top.get(i);
                sb.append(i + 1).append(". ")
                  .append(p.name()).append(" — ")
                  .append(p.qty()).append(" vendu(s) — ")
                  .append(formatXAF(p.revenue())).append(" FCFA\n");
            }
        }

        if (data.pendingSalesCount() > 0) {
            sb.append("\n⏳ Ventes en attente : ").append(data.pendingSalesCount())
              .append(" (").append(formatXAF(data.pendingSalesTotal())).append(" FCFA)");
        }

        return sb.toString();
    }

    /**
     * Format a multi-store combined summary section to append to a report or send separately.
     */
    public String formatCombinedSummary(String date, List<StoreRevenuePair> stores, int totalRevenue, int totalSales) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📊 Résumé multi-boutiques — ").append(date).append("\n");
        sb.append("💰 CA Total toutes boutiques : ").append(formatXAF(totalRevenue)).append(" FCFA\n");
        sb.append("🛍 Total ventes : ").append(totalSales).append("\n");
        for (StoreRevenuePair store : stores) {
            sb.append("🏪 ").append(store.name()).append(" : ").append(formatXAF(store.revenue())).append(" FCFA\n");
        }
        return sb.toString();
    }

    public record StoreRevenuePair(String name, int revenue) {}

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String formatXAF(int amount) {
        return NumberFormat.getNumberInstance(FR).format(amount);
    }
}
