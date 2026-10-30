package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.crm.api.ClientSummary;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatCalculator;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 14.4 — the sale → invoice mapping of the POS → Invoicing bridge. */
class SaleInvoiceMapperTest {

    private static long sumTtc(List<LineInput> lines, boolean vatRegistered) {
        List<InvoiceLine> computed = VatCalculator.lines(lines, vatRegistered, new BigDecimal("19.25"), true);
        return VatCalculator.totals(computed).totalTtc();
    }

    @Test
    @DisplayName("no discount: one line per sold line, applied unit price, quantity, STANDARD")
    void noDiscount() {
        List<LineInput> lines = SaleInvoiceMapper.lines(List.of(new Line("Riz 5kg", 1000, 2),
                new Line("Savon", 500, 1)), 2500);

        assertThat(lines).containsExactly(
                new LineInput("Riz 5kg", new BigDecimal("2"), 1000, VatTreatment.STANDARD),
                new LineInput("Savon", BigDecimal.ONE, 500, VatTreatment.STANDARD));
        assertThat(sumTtc(lines, false)).isEqualTo(2500);
    }

    @Test
    @DisplayName("divisible discount: 2 × 1 000 + 1 × 500, discount 250 → 2 × 900 + 1 × 450 = 2 250")
    void discountDivisible() {
        List<LineInput> lines = SaleInvoiceMapper.lines(List.of(new Line("Riz", 1000, 2), new Line("Savon", 500, 1)),
                2250);

        assertThat(lines).containsExactly(
                new LineInput("Riz", new BigDecimal("2"), 900, VatTreatment.STANDARD),
                new LineInput("Savon", BigDecimal.ONE, 450, VatTreatment.STANDARD));
        assertThat(sumTtc(lines, false)).isEqualTo(2250);
        assertThat(sumTtc(lines, true)).as("VAT-registered seller: TTC unchanged").isEqualTo(2250);
    }

    @Test
    @DisplayName("non-divisible line: 3 × 1 000, discount 100 → « Produit (× 3) » quantity 1 at 2 900")
    void discountNotDivisible() {
        List<LineInput> lines = SaleInvoiceMapper.lines(List.of(new Line("Produit", 1000, 3)), 2900);

        assertThat(lines).containsExactly(new LineInput("Produit (× 3)", BigDecimal.ONE, 2900, VatTreatment.STANDARD));
    }

    @Test
    @DisplayName("rounding remainder goes to the largest line; the total always equals the sale total")
    void remainderOnLargestLine() {
        // 3 lines of 1 000, discount 100: shares 33 + 33 + 33 = 99, remainder 1 on the first largest line
        List<LineInput> lines = SaleInvoiceMapper.lines(List.of(new Line("A", 1000, 1), new Line("B", 1000, 1),
                new Line("C", 1000, 1)), 2900);

        assertThat(lines).extracting(LineInput::unitPrice).containsExactly(966L, 967L, 967L);
        assertThat(sumTtc(lines, false)).isEqualTo(2900);
    }

    @Test
    @DisplayName("mixed totals: the invoice TTC equals the sale total for many discounts")
    void totalsAlwaysMatch() {
        List<Line> sold = List.of(new Line("A", 1250, 3), new Line("B", 775, 7), new Line("C", 99, 1),
                new Line("D", 0, 2));
        long sum = 1250 * 3 + 775 * 7 + 99;
        for (long discount = 0; discount <= sum; discount += 37) {
            List<LineInput> lines = SaleInvoiceMapper.lines(sold, sum - discount);
            assertThat(sumTtc(lines, true)).as("discount %d", discount).isEqualTo(sum - discount);
        }
        assertThat(sumTtc(SaleInvoiceMapper.lines(sold, 0), false)).isZero();
    }

    @Test
    @DisplayName("inconsistent sale (total above the lines, no line, bad quantity) is refused")
    void inconsistent() {
        assertThatThrownBy(() -> SaleInvoiceMapper.lines(List.of(new Line("A", 1000, 1)), 1500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SaleInvoiceMapper.lines(List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SaleInvoiceMapper.lines(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SaleInvoiceMapper.lines(List.of(new Line("A", 1000, 0)), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank product name → « Article »")
    void unnamedProduct() {
        assertThat(SaleInvoiceMapper.lines(List.of(new Line(" ", 100, 1)), 100).get(0).description())
                .isEqualTo("Article");
    }

    @Test
    @DisplayName("client: CRM client when known, « Client comptoir » (no id) otherwise")
    void client() {
        UUID id = UUID.randomUUID();
        assertThat(SaleInvoiceMapper.client(Optional.of(new ClientSummary(id, "Awa Ngono", "+237690000111", false))))
                .isEqualTo(new ClientInfo(id, "Awa Ngono", null, null, "+237690000111"));
        assertThat(SaleInvoiceMapper.client(Optional.empty()))
                .isEqualTo(new ClientInfo(null, "Client comptoir", null, null, null));
    }

    @Test
    @DisplayName("payment method labels")
    void paymentMethod() {
        assertThat(SaleInvoiceMapper.paymentMethod("CASH")).isEqualTo("Espèces");
        assertThat(SaleInvoiceMapper.paymentMethod("MOBILE_MONEY")).isEqualTo("Mobile Money");
        assertThat(SaleInvoiceMapper.paymentMethod(null)).isNull();
        assertThat(SaleInvoiceMapper.paymentMethod("CHEQUE")).isNull();
    }
}
