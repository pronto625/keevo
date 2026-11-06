package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * Story 20.5 (fusion Keevo × Bref, FUS-FR19) — the document-level terms of an invoice or a quote, entered with its
 * content and frozen at issue like the rest:
 * <ul>
 *   <li>{@code discount} — a whole-XAF amount taken off the subtotal, before VAT (≤ the subtotal, checked by the
 *       calculator);</li>
 *   <li>{@code deliveryFee} — added after VAT, outside the VAT base;</li>
 *   <li>{@code vatRate} — the rate requested for this document (percent, 0–100, 2 decimals); {@code null} = the
 *       seller's rate;</li>
 *   <li>{@code vatExempt} — no VAT on the whole document (mention « Exonéré de TVA »);</li>
 *   <li>{@code dueDate} — when the client must have paid; never before the issue date once issued.</li>
 * </ul>
 * {@link #NONE} = every document before 20.5 (and every V1 body): no discount, no delivery, the seller's rate.
 */
public record InvoiceTerms(long discount, long deliveryFee, BigDecimal vatRate, boolean vatExempt,
                           LocalDate dueDate) {

    public static final InvoiceTerms NONE = new InvoiceTerms(0, 0, null, false, null);

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public InvoiceTerms {
        if (discount < 0) {
            throw invalid("La remise ne peut pas être négative");
        }
        if (deliveryFee < 0) {
            throw invalid("Les frais de livraison ne peuvent pas être négatifs");
        }
        if (vatRate != null) {
            if (vatRate.signum() < 0 || vatRate.compareTo(HUNDRED) > 0 || vatRate.stripTrailingZeros().scale() > 2) {
                throw invalid("Le taux de TVA doit être compris entre 0 et 100 %, avec au plus 2 décimales");
            }
            vatRate = vatRate.setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    /** A discount or a delivery fee: the document totals differ from the sums of its lines. */
    public boolean hasAdjustments() {
        return discount > 0 || deliveryFee > 0;
    }

    /** The rate applied to the document's STANDARD lines: 0 when exempt, else the requested one or the seller's. */
    public BigDecimal effectiveRate(BigDecimal sellerRate) {
        if (vatExempt) {
            return BigDecimal.ZERO.setScale(2);
        }
        return vatRate != null ? vatRate : sellerRate;
    }

    /** The due date can never precede the issue date ({@code VALIDATION_ERROR}, French). */
    public void assertDueDateFrom(LocalDate issueDate) {
        if (dueDate != null && issueDate != null && dueDate.isBefore(issueDate)) {
            throw invalid("La date d'échéance ne peut pas précéder la date d'émission de la facture");
        }
    }

    static DomainException invalid(String reason) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid invoice terms", Map.of("reason", reason));
    }
}
