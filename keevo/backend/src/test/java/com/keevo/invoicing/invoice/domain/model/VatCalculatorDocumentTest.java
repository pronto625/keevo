package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VatCalculator.compute — discount, delivery, document VAT (Story 20.5)")
class VatCalculatorDocumentTest {

    private static final BigDecimal CM = new BigDecimal("19.25");

    private static LineInput line(String qty, long price) {
        return new LineInput("Article", new BigDecimal(qty), price, VatTreatment.STANDARD);
    }

    @Test
    @DisplayName("HT mode, Bref case 1: subtotal 317 700, discount 2 500, delivery 2 000 → VAT 60 676, TTC 377 876")
    void brefCase1() {
        InvoiceTotals t = VatCalculator.compute(List.of(line("1", 5_000), line("2", 3_500), line("1", 600),
                line("2", 150_000), line("1", 100), line("1", 5_000)), true, CM, false, 2_500, 2_000).totals();
        assertThat(t).isEqualTo(new InvoiceTotals(315_200, 60_676, 377_876, 317_700, 2_500, 2_000));
    }

    @Test
    @DisplayName("HT mode, exact .5: (300 000 − 15 000) × 19,25 % = 54 862,5 → 54 863, TTC 342 363")
    void halfUp() {
        InvoiceTotals t = VatCalculator.compute(List.of(line("10", 5_000), line("1000", 100), line("1", 150_000)),
                true, CM, false, 15_000, 2_500).totals();
        assertThat(t.totalVat()).isEqualTo(54_863);
        assertThat(t.totalTtc()).isEqualTo(342_363);
    }

    @Test
    @DisplayName("HT mode: VAT rounded once on the document (Bref), not per line")
    void htVatOnceOnDocument() {
        // per line: 3 × round(0.5 × 19.25 %…) — 3 lines of 2 F: per-line VAT 0 each, document VAT round(1.155) = 1
        VatCalculator.Computation c = VatCalculator.compute(List.of(line("1", 2), line("1", 2), line("1", 2)), true,
                CM, false, 0, 0);
        assertThat(c.lines()).allSatisfy(l -> assertThat(l.totalVat()).isZero());
        assertThat(c.totals().totalVat()).isEqualTo(1);
        assertThat(c.totals().totalTtc()).isEqualTo(7);
    }

    @Test
    @DisplayName("TTC mode without discount nor delivery: exactly the sums of the lines (today's result)")
    void ttcUnchanged() {
        List<LineInput> in = List.of(line("1", 1_000), line("3", 11_925),
                new LineInput("Exo", BigDecimal.ONE, 500, VatTreatment.EXEMPT));
        VatCalculator.Computation c = VatCalculator.compute(in, true, CM, true, 0, 0);
        InvoiceTotals sums = VatCalculator.totals(VatCalculator.lines(in, true, CM, true));
        assertThat(c.totals()).isEqualTo(sums);
        assertThat(c.totals().subtotal()).isEqualTo(sums.totalTtc());
    }

    @Test
    @DisplayName("TTC mode with discount: lines TTC 119 250 (VAT 19 250), discount 19 250 → TTC 100 000, VAT reduced")
    void ttcDiscount() {
        InvoiceTotals t = VatCalculator.compute(List.of(line("1", 119_250)), true, CM, true, 19_250, 0).totals();
        long vat = new BigDecimal(19_250L * 100_000L).divide(new BigDecimal(119_250), 0,
                java.math.RoundingMode.HALF_UP).longValueExact();
        assertThat(t).isEqualTo(new InvoiceTotals(100_000 - vat, vat, 100_000, 119_250, 19_250, 0));
        assertThat(vat).isEqualTo(16_143);
    }

    @Test
    @DisplayName("delivery outside the VAT base, in the TTC: TTC = HT + VAT + delivery (both modes)")
    void delivery() {
        InvoiceTotals ht = VatCalculator.compute(List.of(line("1", 10_000)), true, CM, false, 0, 1_500).totals();
        assertThat(ht).isEqualTo(new InvoiceTotals(10_000, 1_925, 13_425, 10_000, 0, 1_500));
        InvoiceTotals ttc = VatCalculator.compute(List.of(line("1", 11_925)), true, CM, true, 0, 1_500).totals();
        assertThat(ttc).isEqualTo(new InvoiceTotals(10_000, 1_925, 13_425, 11_925, 0, 1_500));
    }

    @Test
    @DisplayName("HT mode, mixed treatments: VAT base = net × standard share of the subtotal")
    void mixedTreatments() {
        InvoiceTotals t = VatCalculator.compute(List.of(line("1", 10_000),
                new LineInput("Exo", BigDecimal.ONE, 10_000, VatTreatment.EXEMPT)), true, CM, false, 2_000, 0)
                .totals();
        // base = 18 000 × 10 000 / 20 000 = 9 000; VAT = 1 732.5 → 1 733
        assertThat(t.totalVat()).isEqualTo(1_733);
        assertThat(t.totalTtc()).isEqualTo(18_000 + 1_733);
    }

    @Test
    @DisplayName("discount above the subtotal → VALIDATION_ERROR (French reason); equal → total 0 + delivery")
    void discountTooBig() {
        assertThatThrownBy(() -> VatCalculator.compute(List.of(line("1", 5_000)), true, CM, false, 5_001, 0))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getDetails().get("reason").toString()).contains("La remise ne peut pas dépasser");
                });
        assertThat(VatCalculator.compute(List.of(line("1", 5_000)), true, CM, false, 5_000, 1_000).totals())
                .isEqualTo(new InvoiceTotals(0, 0, 1_000, 5_000, 5_000, 1_000));
    }

    @Test
    @DisplayName("seller not VAT-registered: no VAT in either mode, discount and delivery still applied")
    void notRegistered() {
        assertThat(VatCalculator.compute(List.of(line("2", 5_000)), false, CM, false, 500, 250).totals())
                .isEqualTo(new InvoiceTotals(9_500, 0, 9_750, 10_000, 500, 250));
    }
}
