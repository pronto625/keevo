package com.keevo.invoicing.invoice.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VatCalculator — XAF, HALF_UP to the franc (Story 14.1)")
class VatCalculatorTest {

    private static final BigDecimal RATE = new BigDecimal("19.25");

    private static LineInput line(String qty, long price, VatTreatment t) {
        return new LineInput("Article", new BigDecimal(qty), price, t);
    }

    @Test
    @DisplayName("VAT registered, TTC prices: 1 × 11 925 → HT 10 000, VAT 1 925, TTC 11 925")
    void ttcStandard() {
        InvoiceLine l = VatCalculator.lines(List.of(line("1", 11_925, VatTreatment.STANDARD)), true, RATE, true).get(0);
        assertThat(l.totalHt()).isEqualTo(10_000);
        assertThat(l.totalVat()).isEqualTo(1_925);
        assertThat(l.totalTtc()).isEqualTo(11_925);
        assertThat(l.vatRate()).isEqualByComparingTo("19.25");
        assertThat(l.vatMention()).isNull();
    }

    @Test
    @DisplayName("VAT registered, HT prices: 3 × 1 000 → HT 3 000, VAT round(577.5) = 578, TTC 3 578")
    void htStandardRoundsHalfUp() {
        InvoiceLine l = VatCalculator.lines(List.of(line("3", 1_000, VatTreatment.STANDARD)), true, RATE, false).get(0);
        assertThat(l.totalHt()).isEqualTo(3_000);
        assertThat(l.totalVat()).isEqualTo(578);
        assertThat(l.totalTtc()).isEqualTo(3_578);
    }

    @Test
    @DisplayName("TTC prices with rounding: 1 × 1 000 → HT round(838.57) = 839, VAT 161")
    void ttcRounding() {
        InvoiceLine l = VatCalculator.lines(List.of(line("1", 1_000, VatTreatment.STANDARD)), true, RATE, true).get(0);
        assertThat(l.totalHt()).isEqualTo(839);
        assertThat(l.totalVat()).isEqualTo(161);
        assertThat(l.totalTtc()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("fractional quantity: 2.5 × 333 = 832.5 → 833 (HT prices, exempt)")
    void fractionalQuantity() {
        InvoiceLine l = VatCalculator.lines(List.of(line("2.5", 333, VatTreatment.EXEMPT)), true, RATE, false).get(0);
        assertThat(l.totalHt()).isEqualTo(833);
        assertThat(l.totalVat()).isZero();
        assertThat(l.totalTtc()).isEqualTo(833);
    }

    @Test
    @DisplayName("EXEMPT → 0 %, « Exonérée »; STATE_BORNE → 0 %, « Prise en charge État »")
    void exemptions() {
        List<InvoiceLine> lines = VatCalculator.lines(List.of(line("1", 5_000, VatTreatment.EXEMPT),
                line("2", 1_000, VatTreatment.STATE_BORNE)), true, RATE, true);
        assertThat(lines.get(0).vatRate()).isEqualByComparingTo("0");
        assertThat(lines.get(0).vatMention()).isEqualTo("Exonérée");
        assertThat(lines.get(0).totalHt()).isEqualTo(5_000);
        assertThat(lines.get(1).vatMention()).isEqualTo("Prise en charge État");
        assertThat(lines.get(1).totalTtc()).isEqualTo(2_000);
        assertThat(lines.get(1).totalVat()).isZero();
        assertThat(lines).extracting(InvoiceLine::position).containsExactly(1, 2);
    }

    @Test
    @DisplayName("not VAT-registered: every line at 0 %, « TVA non applicable »")
    void notRegistered() {
        List<InvoiceLine> lines = VatCalculator.lines(List.of(line("1", 11_925, VatTreatment.STANDARD),
                line("1", 100, VatTreatment.EXEMPT)), false, RATE, true);
        assertThat(lines).allSatisfy(l -> {
            assertThat(l.vatRate()).isEqualByComparingTo("0");
            assertThat(l.totalVat()).isZero();
            assertThat(l.totalHt()).isEqualTo(l.totalTtc());
        });
        assertThat(VatCalculator.vatMention(false)).isEqualTo("TVA non applicable");
        assertThat(VatCalculator.vatMention(true)).isNull();
    }

    @Test
    @DisplayName("totals = sums of the lines")
    void totals() {
        List<InvoiceLine> lines = VatCalculator.lines(List.of(line("1", 11_925, VatTreatment.STANDARD),
                line("1", 1_000, VatTreatment.STANDARD), line("1", 500, VatTreatment.EXEMPT)), true, RATE, true);
        InvoiceTotals t = VatCalculator.totals(lines);
        assertThat(t).isEqualTo(new InvoiceTotals(10_000 + 839 + 500, 1_925 + 161, 11_925 + 1_000 + 500));
        assertThat(VatCalculator.totals(List.of())).isEqualTo(InvoiceTotals.ZERO);
    }

    @Test
    @DisplayName("line input rules: description, quantity > 0 with ≤ 3 decimals, price ≥ 0, STANDARD by default")
    void inputRules() {
        assertThatThrownBy(() -> new LineInput(" ", BigDecimal.ONE, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineInput("a", BigDecimal.ZERO, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineInput("a", new BigDecimal("1.0001"), 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineInput("a", BigDecimal.ONE, -1, null)).isInstanceOf(IllegalArgumentException.class);
        LineInput ok = new LineInput(" Sac de riz ", new BigDecimal("1.50"), 0, null);
        assertThat(ok.treatment()).isEqualTo(VatTreatment.STANDARD);
        assertThat(ok.description()).isEqualTo("Sac de riz");
        assertThat(ok.quantity()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("an amount beyond the storable range is refused")
    void overflow() {
        LineInput huge = new LineInput("a", new BigDecimal("999999999.999"), Long.MAX_VALUE / 4, null);
        assertThatThrownBy(() -> VatCalculator.lines(List.of(huge), true, RATE, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
