package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Line and total computation of an invoice (Story 14.1). Amounts are XAF francs, rounded HALF_UP to the franc:
 * <ul>
 *   <li>rate = the seller's rate for a {@code STANDARD} line of a VAT-registered seller, else 0 %;</li>
 *   <li>prices VAT included: TTC = round(qty × price), HT = round(TTC / (1 + rate)), VAT = TTC − HT;</li>
 *   <li>prices VAT excluded: HT = round(qty × price), VAT = round(HT × rate), TTC = HT + VAT.</li>
 * </ul>
 * Totals are the sums of the lines ({@link #totals}).
 *
 * <p>Story 20.5 — {@link #compute}: the document step, a <b>strategy by pricing mode</b> chosen from the invoice's frozen
 * {@code pricesIncludeVat} (both strategies share the line computation above):
 * <ul>
 *   <li><b>prices without VAT — Bref's formula exactly</b> ({@code api-bref invoice.service.ts}): subtotal = Σ line HT;
 *       net = subtotal − discount; VAT base = net × (Σ STANDARD-rated line HT / subtotal); VAT = HALF_UP(base × rate /
 *       100) once on the document; TTC = net + VAT + delivery. The lines' VAT / TTC are the per-line split, for
 *       display only;</li>
 *   <li><b>prices VAT included — Keevo</b>: the lines as today; with a discount, TTC before delivery = Σ line TTC −
 *       discount and the VAT is reduced proportionally, VAT = HALF_UP(Σ line VAT × (Σ TTC − discount) / Σ TTC),
 *       HT = TTC − VAT; delivery added after, outside VAT. Discount = delivery = 0 gives exactly the sums of the lines
 *       (every invoice and POS invoice before 20.5).</li>
 * </ul>
 * A discount above the subtotal → {@code VALIDATION_ERROR}. Cost: one pass over the lines.
 */
public final class VatCalculator {

    /** Printed on the invoice of a seller that is not VAT-registered. */
    public static final String VAT_NOT_APPLICABLE = "TVA non applicable";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_AMOUNT = BigDecimal.valueOf(Long.MAX_VALUE / 2);

    private VatCalculator() {
    }

    public static List<InvoiceLine> lines(List<LineInput> inputs, boolean vatRegistered, BigDecimal sellerRate,
                                          boolean pricesIncludeVat) {
        List<InvoiceLine> lines = new ArrayList<>();
        int position = 1;
        for (LineInput input : inputs) {
            BigDecimal rate = vatRegistered && input.treatment() == VatTreatment.STANDARD
                    ? sellerRate : BigDecimal.ZERO;
            rate = rate.setScale(2, RoundingMode.HALF_UP);
            BigDecimal gross = input.quantity().multiply(BigDecimal.valueOf(input.unitPrice()));
            long ht;
            long vat;
            long ttc;
            if (pricesIncludeVat) {
                ttc = franc(gross);
                ht = franc(BigDecimal.valueOf(ttc).multiply(HUNDRED).divide(HUNDRED.add(rate), 0, RoundingMode.HALF_UP));
                vat = ttc - ht;
            } else {
                ht = franc(gross);
                vat = franc(BigDecimal.valueOf(ht).multiply(rate).divide(HUNDRED, 0, RoundingMode.HALF_UP));
                ttc = ht + vat;
            }
            lines.add(new InvoiceLine(position++, input.description(), input.quantity(), input.unitPrice(),
                    input.treatment(), rate, ht, vat, ttc, input.productId(), input.reference()));
        }
        return List.copyOf(lines);
    }

    /** Story 20.5 — the computed lines and the document's totals. */
    public record Computation(List<InvoiceLine> lines, InvoiceTotals totals) {
    }

    /**
     * Story 20.5 — the lines and the document totals (see the class comment). {@code rate} = the rate of the STANDARD
     * lines of a VAT-registered seller ({@link InvoiceTerms#effectiveRate}: 0 when the document is exempt).
     */
    public static Computation compute(List<LineInput> inputs, boolean vatRegistered, BigDecimal rate,
                                      boolean pricesIncludeVat, long discount, long delivery) {
        if (discount < 0 || delivery < 0) {
            throw new IllegalArgumentException("Discount and delivery must be >= 0");
        }
        List<InvoiceLine> lines = lines(inputs, vatRegistered, rate, pricesIncludeVat);
        long sumHt = 0;
        long sumVat = 0;
        long sumTtc = 0;
        long standardHt = 0;
        for (InvoiceLine line : lines) {
            sumHt = Math.addExact(sumHt, line.totalHt());
            sumVat = Math.addExact(sumVat, line.totalVat());
            sumTtc = Math.addExact(sumTtc, line.totalTtc());
            if (line.vatRate().signum() > 0) {
                standardHt = Math.addExact(standardHt, line.totalHt());
            }
        }
        long subtotal = pricesIncludeVat ? sumTtc : sumHt;
        if (discount > subtotal) {
            throw InvoiceTerms.invalid("La remise ne peut pas dépasser le sous-total ("
                    + com.keevo.shared.domain.XafAmounts.group(subtotal) + " FCFA)");
        }
        long net = subtotal - discount;
        long ht;
        long vat;
        if (pricesIncludeVat) {
            vat = discount == 0 || sumTtc == 0 ? sumVat
                    : franc(BigDecimal.valueOf(sumVat).multiply(BigDecimal.valueOf(net))
                    .divide(BigDecimal.valueOf(sumTtc), 0, RoundingMode.HALF_UP));
            ht = net - vat;
        } else {
            BigDecimal applied = vatRegistered && rate != null ? rate : BigDecimal.ZERO;
            vat = subtotal == 0 || standardHt == 0 || applied.signum() == 0 ? 0
                    : franc(BigDecimal.valueOf(net).multiply(BigDecimal.valueOf(standardHt)).multiply(applied)
                    .divide(BigDecimal.valueOf(subtotal).multiply(HUNDRED), 0, RoundingMode.HALF_UP));
            ht = net;
        }
        long ttc = Math.addExact(Math.addExact(ht, vat), delivery);
        franc(BigDecimal.valueOf(ttc));
        return new Computation(lines, new InvoiceTotals(ht, vat, ttc, subtotal, discount, delivery));
    }

    /** The sums of the lines (no discount, no delivery). */
    public static InvoiceTotals totals(List<InvoiceLine> lines) {
        long ht = 0;
        long vat = 0;
        long ttc = 0;
        for (InvoiceLine line : lines) {
            ht = Math.addExact(ht, line.totalHt());
            vat = Math.addExact(vat, line.totalVat());
            ttc = Math.addExact(ttc, line.totalTtc());
        }
        return new InvoiceTotals(ht, vat, ttc);
    }

    /** « TVA non applicable » for a seller that is not VAT-registered, else {@code null}. */
    public static String vatMention(boolean vatRegistered) {
        return vatRegistered ? null : VAT_NOT_APPLICABLE;
    }

    /** Story 20.5 — printed on a document exempted from VAT as a whole. */
    public static final String VAT_EXEMPT = "Exonéré de TVA";

    /** Story 20.5 — « TVA non applicable » (seller not VAT-registered), else « Exonéré de TVA » (exempt), else none. */
    public static String vatMention(boolean vatRegistered, boolean vatExempt) {
        if (!vatRegistered) {
            return VAT_NOT_APPLICABLE;
        }
        return vatExempt ? VAT_EXEMPT : null;
    }

    private static long franc(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Amount too large");
        }
        return rounded.longValueExact();
    }
}
