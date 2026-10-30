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
 * Totals are the sums of the lines.
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
                    input.treatment(), rate, ht, vat, ttc));
        }
        return List.copyOf(lines);
    }

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

    private static long franc(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Amount too large");
        }
        return rounded.longValueExact();
    }
}
