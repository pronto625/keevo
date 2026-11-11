package com.keevo.invoicing.invoice.adapter.in.api;

import com.keevo.invoicing.api.InvoiceImportPort.Document;
import com.keevo.invoicing.api.InvoiceImportPort.Line;
import com.keevo.invoicing.api.InvoiceImportPort.Payment;
import com.keevo.invoicing.api.InvoiceImportPort.Seller;
import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceImportWriter.CollectionInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 24.4 — the invoicing import port's checks and its collection-state rule. */
@DisplayName("24.4 — invoicing import port: checks and collection state")
class InvoiceImportApiAdapterTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-03-20");
    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00Z");

    private static Document document(boolean quote, String status, String number, long subtotal, long discount,
                                     String reference) {
        return new Document(UUID.randomUUID(), quote, status, number, quote ? null : TODAY, null, "Client", null,
                null, null, null, null, null, null, new Seller("Boutique", null, null, null, null), false,
                new BigDecimal("0.00"), subtotal, discount, 0, subtotal - discount, 0, subtotal - discount,
                quote ? null : T0, "PAID".equals(status) ? T0 : null, null,
                "CANCELLED".equals(status) ? T0 : null, "CANCELLED".equals(status) ? "Annulée" : null, null, null,
                null, null, null, reference, null, T0, T0, null);
    }

    private static InvoicePayment paid(long amount) {
        return new InvoicePayment(UUID.randomUUID(), null, amount, null, T0, null, null, false);
    }

    private static InvoicePayment due(long amount, String day) {
        return new InvoicePayment(UUID.randomUUID(), null, amount, LocalDate.parse(day), null, null, null, false);
    }

    @Test
    @DisplayName("collection state: PAID kept for a paid document, computed (OVERDUE included) for an issued one, none for quotes and cancelled")
    void state() {
        UUID id = UUID.randomUUID();
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, false, "PAID", 1000, null,
                List.of(paid(300))), TODAY)).isEqualTo(PaymentState.PAID);
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, false, "ISSUED", 1000, null,
                List.of(paid(300), due(700, "2026-03-10"))), TODAY)).isEqualTo(PaymentState.OVERDUE);
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, false, "ISSUED", 1000, null,
                List.of(paid(300), due(700, "2026-04-10"))), TODAY)).isEqualTo(PaymentState.PARTIAL);
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, false, "ISSUED", 1000,
                LocalDate.parse("2026-03-01"), List.of()), TODAY)).isEqualTo(PaymentState.OVERDUE);
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, false, "ISSUED", 1000, null, List.of()),
                TODAY)).isEqualTo(PaymentState.UNPAID);
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, false, "ISSUED", 1000, null,
                List.of(paid(1000))), TODAY)).isEqualTo(PaymentState.PAID);
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, false, "CANCELLED", 1000, null,
                List.of(paid(1000))), TODAY)).isNull();
        assertThat(InvoiceImportApiAdapter.state(new CollectionInput(id, true, "DRAFT", 1000, null, List.of()),
                TODAY)).isNull();
    }

    @Test
    @DisplayName("checks: quote invariants, invoice number and dates, discount, reference format, line and payment rules")
    void checks() {
        assertThatCode(() -> InvoiceImportApiAdapter.check(document(false, "ISSUED", "BRF-ABCDEF12", 100, 0,
                "BRF-ABCDEF12"))).doesNotThrowAnyException();
        assertThatCode(() -> InvoiceImportApiAdapter.check(document(true, "DRAFT", null, 100, 0, "BRF-ABCDEF12")))
                .doesNotThrowAnyException();
        assertThatCode(() -> InvoiceImportApiAdapter.check(document(false, "CANCELLED", "BRF-ABCDEF12-2", 100, 0,
                null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(document(true, "ISSUED", null, 100, 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(document(true, "DRAFT", "BRF-X", 100, 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(document(false, "DRAFT", "BRF-X", 100, 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(document(false, "ISSUED", null, 100, 0, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(document(false, "ISSUED", "BRF-A", 100, 200, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(document(false, "ISSUED", "BRF-A", 100, 0,
                "BRF-abc"))).isInstanceOf(IllegalArgumentException.class);

        UUID doc = UUID.randomUUID();
        assertThatCode(() -> InvoiceImportApiAdapter.check(new Line(UUID.randomUUID(), doc, 1, "Riz",
                new BigDecimal("1.250"), 100, BigDecimal.ZERO, 125, 0, 125, null, null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(new Line(UUID.randomUUID(), doc, 1, "Riz",
                BigDecimal.ZERO, 100, BigDecimal.ZERO, 0, 0, 0, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(new Line(UUID.randomUUID(), doc, 1, "x".repeat(501),
                BigDecimal.ONE, 100, BigDecimal.ZERO, 100, 0, 100, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(new Payment(UUID.randomUUID(), doc, 0, TODAY, null,
                null, null, null, T0, T0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(new Payment(UUID.randomUUID(), doc, 10, null, null,
                null, null, null, T0, T0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceImportApiAdapter.check(new Seller("x".repeat(201), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
