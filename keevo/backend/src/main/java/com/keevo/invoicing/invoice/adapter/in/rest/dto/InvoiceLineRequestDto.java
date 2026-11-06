package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A free invoice line (Story 14.1): unit price in francs (VAT included or excluded per the seller settings);
 * {@code vatTreatment} {@code STANDARD} (default), {@code EXEMPT} (« Exonérée ») or {@code STATE_BORNE}
 * (« Prise en charge État »). Story 20.5: {@code productId} (the catalogue product, optional, not checked against the
 * catalogue) and {@code reference} (optional, ≤ 100).
 */
public record InvoiceLineRequestDto(
        @NotBlank(message = "La désignation de la ligne est requise")
        @Size(max = 500, message = "La désignation ne doit pas dépasser 500 caractères")
        String description,
        @NotNull(message = "La quantité est requise")
        @DecimalMin(value = "0", inclusive = false, message = "La quantité doit être supérieure à 0")
        @Digits(integer = 9, fraction = 3, message = "La quantité accepte au plus 3 décimales")
        BigDecimal quantity,
        @NotNull(message = "Le prix unitaire est requis")
        @Min(value = 0, message = "Le prix unitaire ne peut pas être négatif")
        @Max(value = 1_000_000_000_000L, message = "Le prix unitaire est trop élevé")
        Long unitPrice,
        @Pattern(regexp = "STANDARD|EXEMPT|STATE_BORNE", message = "Le traitement de TVA de la ligne est invalide")
        String vatTreatment,
        UUID productId,
        @Size(max = 100, message = "La référence de la ligne ne doit pas dépasser 100 caractères")
        String reference) {

    /** Pre-20.5 shape: no product, no reference. */
    public InvoiceLineRequestDto(String description, BigDecimal quantity, Long unitPrice, String vatTreatment) {
        this(description, quantity, unitPrice, vatTreatment, null, null);
    }
}
