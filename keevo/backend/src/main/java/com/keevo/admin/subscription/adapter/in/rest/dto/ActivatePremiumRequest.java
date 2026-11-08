package com.keevo.admin.subscription.adapter.in.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * ActivatePremiumRequest — body of {@code POST /api/v1/admin/tenants/{tenantId}/plan/premium} (Story 9-2).
 *
 * <p>Dates are strings so that a malformed value is a 422 field error, not an unreadable body.
 *
 * @param expiresAt ISO date {@code yyyy-MM-dd}, last Premium day (WAT), after today
 * @param amountXaf amount paid, &gt; 0
 * @param method    MOBILE_MONEY | CASH | BANK_TRANSFER | OTHER
 * @param paidAt    optional ISO date-time or date of the payment, default now
 * @param note      optional, 500 chars max
 * @param planCode  Story 19.7b — optional paid plan code of the catalogue (kind PAID, not archived); absent → the
 *                  default paid plan of {@code plan_settings}
 * @param cycleCode Story 21.10 — optional billing cycle paid for (a live cycle of the catalogue), stored with the
 *                  payment for the revenue per plan and cycle; absent or empty = free-form period
 */
public record ActivatePremiumRequest(
        @NotBlank(message = "Date d'expiration requise")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date d'expiration invalide")
        String expiresAt,

        @NotNull(message = "Montant requis")
        @Positive(message = "Le montant doit être supérieur à 0")
        @Max(value = Integer.MAX_VALUE, message = "Montant trop élevé")
        Long amountXaf,

        @NotBlank(message = "Moyen de paiement requis")
        @Pattern(regexp = "MOBILE_MONEY|CASH|BANK_TRANSFER|OTHER", message = "Moyen de paiement inconnu")
        String method,

        String paidAt,

        @Size(max = 500, message = "500 caractères maximum")
        String note,

        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,29}$", message = "Code de plan invalide")
        String planCode,

        @Pattern(regexp = "^$|^[A-Za-z][A-Za-z0-9_]{1,29}$", message = "Code de durée invalide")
        String cycleCode
) {

    /** Without a billing cycle (clients before Story 21.10). */
    public ActivatePremiumRequest(String expiresAt, Long amountXaf, String method, String paidAt, String note,
                                  String planCode) {
        this(expiresAt, amountXaf, method, paidAt, note, planCode, null);
    }
}
