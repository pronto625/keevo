package com.keevo.admin.crossaccount.domain.model;

import com.keevo.admin.crosstenant.CursorPage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Story 20.13 — read models of the super-admin cross-account lists (Bref {@code customer-admin},
 * {@code product-admin}, {@code expense-admin}). Every row names its account ({@code tenantId} for navigation,
 * {@code tenantName} for display); codes come with their French label.
 */
public final class CrossAccountViews {

    private CrossAccountViews() {
    }

    public record CustomerItem(String tenantId, String tenantName, String id, String name, String phone, String email,
                               String storeId, String storeName, boolean marketingConsent, Instant createdAt,
                               Instant updatedAt) {
    }

    public record CustomerDetail(String tenantId, String tenantName, String id, String name, String phone,
                                 String email, String storeId, String storeName, String clientType,
                                 String clientTypeLabel, String niu, String rccm, String address, String contactName,
                                 String website, LocalDate birthDate, String notes, boolean archived,
                                 boolean marketingConsent, Instant marketingConsentAt, Instant marketingOptOutAt,
                                 Instant createdAt, Instant updatedAt) {
    }

    public record ProductItem(String tenantId, String tenantName, String id, String name, long price,
                              int stockQuantity, boolean trackStock, String status, String statusLabel,
                              String categoryName, String barcode, Instant updatedAt) {
    }

    public record ProductDetail(String tenantId, String tenantName, String id, String name, String description,
                                long price, long buyPrice, int stockQuantity, int minimumThreshold,
                                boolean trackStock, String status, String statusLabel, String categoryName,
                                String barcode, Instant createdAt, Instant updatedAt) {
    }

    public record ExpenseItem(String tenantId, String tenantName, String id, String storeId, String storeName,
                              String category, String categoryLabel, BigDecimal amount, LocalDate expenseDate,
                              String label, String paymentMethod, String paymentMethodLabel, Instant createdAt) {
    }

    public record ExpenseDetail(String tenantId, String tenantName, String id, String storeId, String storeName,
                                String category, String categoryLabel, BigDecimal amount, LocalDate expenseDate,
                                String label, String note, String paymentMethod, String paymentMethodLabel,
                                String createdByName, Instant createdAt, Instant updatedAt) {
    }

    /** An expense page and the total amount of every expense matching the filters (Bref {@code totalAmount}). */
    public record ExpensePage(CursorPage<ExpenseItem> page, BigDecimal totalAmount) {
    }

    /** French label of a product state: archived wins, then draft, else active. */
    public static String productStatusLabel(String status, boolean archived) {
        if (archived) {
            return "Archivé";
        }
        return "DRAFT".equals(status) ? "Brouillon" : "Actif";
    }

    /** French label of a client type ({@code PERSON} / {@code COMPANY}, case-insensitive); null when none. */
    public static String clientTypeLabel(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return switch (type.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PERSON" -> "Particulier";
            case "COMPANY" -> "Entreprise";
            default -> "Autre";
        };
    }
}
