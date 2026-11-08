package com.keevo.admin.dashboard.domain.model;

import java.time.Instant;

/**
 * Story 20.13 — read models of the super-admin platform dashboard inherited from Bref ({@code GET /dashboard/overview},
 * {@code /growth}, {@code /top-shops}, {@code /activity}). Accounts are Keevo tenants (a migrated Bref owner is one
 * tenant); users are distinct people; revenue is the 20.9 single source. Months and « this month » are Africa/Douala.
 */
public final class PlatformDashboardViews {

    private PlatformDashboardViews() {
    }

    /** {@code trendPercent}: new this month vs last month, % (null when last month had none). */
    public record UserFigures(long total, long active, long newThisMonth, long newLastMonth, Double trendPercent) {
    }

    public record AccountFigures(long total, long active, long suspended, long newThisMonth, long newLastMonth,
                                 Double trendPercent) {
    }

    public record StoreFigures(long total, long active, long newThisMonth) {
    }

    /** Counted invoices by payment state (20.4) and open quotes. */
    public record DocumentFigures(long invoices, long unpaid, long partiallyPaid, long paid, long overdue,
                                  long quotes) {
    }

    /** {@code trendPercent}: this month vs last month, % (null when last month was 0). */
    public record RevenueFigures(long total, long thisMonth, long lastMonth, Double trendPercent) {
    }

    public record Overview(UserFigures users, AccountFigures accounts, StoreFigures stores,
                           DocumentFigures documents, RevenueFigures revenue) {
    }

    /** {@code month} {@code yyyy-MM}; {@code label} « nov. 2026 » style French label. */
    public record GrowthPoint(String month, String label, int year, long newUsers, long newAccounts, long newStores,
                              long invoicesIssued, long revenue, long documents) {
    }

    public record TopAccount(String tenantId, String name, String ownerName, String status, String statusLabel,
                             long storeCount, long documents, long revenue) {
    }

    public record ActivityItem(String type, String label, String detail, String tenantId, String tenantName,
                               Instant occurredAt) {
    }

    /** The activity types and their French labels. */
    public enum ActivityType {
        ACCOUNT_CREATED("Nouveau compte"),
        USER_CREATED("Nouvel utilisateur"),
        STORE_CREATED("Nouvelle boutique"),
        INVOICE_CREATED("Facture créée"),
        QUOTE_CREATED("Devis créé");

        private final String label;

        ActivityType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** French label of a tenant status. */
    public static String accountStatusLabel(String status) {
        if (status == null) {
            return "Inconnu";
        }
        return switch (status) {
            case "ACTIVE" -> "Actif";
            case "SUSPENDED" -> "Suspendu";
            case "DELETION_PENDING" -> "Suppression programmée";
            default -> "Autre statut";
        };
    }

    public static Double trendPercent(long current, long previous) {
        if (previous == 0) {
            return null;
        }
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }
}
