package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The seller profile (Story 14.1) — request of {@code PUT /invoicing/settings} and response of both verbs. Printed on
 * each invoice when it is issued (CGI art. 150 (5): name, address, NIU, RCCM).
 */
public record InvoicingSettingsDto(
        @Size(max = 200, message = "Le nom du commerce ne doit pas dépasser 200 caractères")
        String legalName,
        @Size(max = 300, message = "L'adresse ne doit pas dépasser 300 caractères")
        String address,
        @Size(max = 30, message = "Le téléphone ne doit pas dépasser 30 caractères")
        String phone,
        @Size(max = 30, message = "Le NIU ne doit pas dépasser 30 caractères")
        String niu,
        @Size(max = 60, message = "Le RCCM ne doit pas dépasser 60 caractères")
        String rccm,
        @NotNull(message = "Indiquez si votre commerce est assujetti à la TVA")
        Boolean vatRegistered,
        @NotNull(message = "Le taux de TVA est requis")
        @DecimalMin(value = "0", message = "Le taux de TVA ne peut pas être négatif")
        @DecimalMax(value = "100", inclusive = false, message = "Le taux de TVA doit être inférieur à 100 %")
        @Digits(integer = 2, fraction = 2, message = "Le taux de TVA accepte au plus 2 décimales")
        BigDecimal vatRate,
        @NotNull(message = "Indiquez si vos prix sont saisis TVA comprise")
        Boolean pricesIncludeVat,
        @Size(max = 1000, message = "Les coordonnées de paiement ne doivent pas dépasser 1000 caractères")
        String paymentDetails,
        @Size(max = 1000, message = "Le pied de page ne doit pas dépasser 1000 caractères")
        String footer) {

    /** A VAT-registered seller needs a rate above 0 (else STANDARD lines would print 0 % without any mention). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @jakarta.validation.constraints.AssertTrue(
            message = "Un commerce assujetti à la TVA doit avoir un taux de TVA supérieur à 0")
    public boolean isVatRateConsistent() {
        return !Boolean.TRUE.equals(vatRegistered) || vatRate == null || vatRate.signum() > 0;
    }

    public InvoicingSettings toDomain() {
        return new InvoicingSettings(legalName, address, phone, niu, rccm, vatRegistered, vatRate, pricesIncludeVat,
                paymentDetails, footer);
    }

    public static InvoicingSettingsDto fromDomain(InvoicingSettings s) {
        return new InvoicingSettingsDto(s.legalName(), s.address(), s.phone(), s.niu(), s.rccm(), s.vatRegistered(),
                s.vatRate(), s.pricesIncludeVat(), s.paymentDetails(), s.footer());
    }
}
