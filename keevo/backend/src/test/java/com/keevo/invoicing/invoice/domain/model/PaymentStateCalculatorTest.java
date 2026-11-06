package com.keevo.invoicing.invoice.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.domain.XafAmounts;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("PaymentStateCalculator + Invoice collection rules (Story 20.4)")
class PaymentStateCalculatorTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", "Akwa, Douala",
            "+237690000000", null, null, false, new BigDecimal("19.25"), true, null, null);
    private static final ClientInfo CLIENT = new ClientInfo(null, "Société Ndolo", null, null, null);

    private static Invoice issued(long total) {
        Invoice draft = Invoice.draft(UUID.randomUUID(), CLIENT,
                List.of(new LineInput("Prestation", BigDecimal.ONE, total, VatTreatment.STANDARD)), SELLER, null,
                Instant.parse("2026-11-01T08:00:00Z"));
        return draft.issue("FAC-2026-000001", LocalDate.parse("2026-11-01"), SELLER,
                Instant.parse("2026-11-01T08:00:00Z"));
    }

    private static InvoicePayment paid(long amount, String day) {
        return InvoicePayment.received(UUID.randomUUID(), null, amount,
                LocalDate.parse(day).atTime(12, 0).atZone(KeevoZones.WAT).toInstant(), null, null);
    }

    private static InvoicePayment due(long amount, String day) {
        return InvoicePayment.scheduled(UUID.randomUUID(), null, amount, LocalDate.parse(day), null, null);
    }

    // ── published calculation cases ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("contract keevo/contracts/invoice-payment-cases.json: every case, through the server's path "
            + "(afterChange → view), gives the served state; every job case the job's rule")
    void contractCases() throws Exception {
        JsonNode contract = new ObjectMapper().readTree(Path.of("..", "contracts", "invoice-payment-cases.json")
                .toFile());
        int count = 0;
        for (JsonNode c : contract.path("cases")) {
            count++;
            String name = c.path("name").asText();
            JsonNode in = c.path("input");
            JsonNode expected = c.path("expected");
            LocalDate today = LocalDate.parse(in.path("today").asText());
            long credited = in.path("creditedTtc").asLong();
            Invoice invoice = issued(in.path("totalTtc").asLong());
            long due = PaymentStateCalculator.amountDue(in.path("totalTtc").asLong(), credited);
            PaymentState stored = stored(in);
            List<InvoicePayment> rows = new ArrayList<>();
            in.path("rows").forEach(r -> rows.add(row(r)));

            if ("cancel".equals(in.path("action").asText())) {
                boolean hasPayments = rows.stream().anyMatch(InvoicePayment::isPaid);
                if (expected.has("error")) {
                    DomainException e = catchThrowableOfType(DomainException.class,
                            () -> invoice.cancel("Erreur", NOW, false, hasPayments));
                    assertThat(e).as(name).isNotNull();
                    assertThat(e.getErrorCode().name()).as(name).isEqualTo(expected.path("error").asText());
                } else {
                    assertThat(invoice.cancel("Erreur", NOW, false, hasPayments).status()).as(name)
                            .isEqualTo(InvoiceStatus.CANCELLED);
                }
                continue;
            }
            PaymentStateCalculator.Collection served;
            if (in.has("newRow")) {
                InvoicePayment added = row(in.path("newRow"));
                DomainException e = catchThrowableOfType(DomainException.class, () -> {
                    if (!added.isPaid()) {
                        PaymentStateCalculator.assertDueDateNotPast(added.dueDate(), today);
                    }
                    PaymentStateCalculator.assertWithinDue(due, rows, added.amount());
                });
                if (expected.has("error")) {
                    assertThat(e).as(name).isNotNull();
                    assertThat(e.getErrorCode().name()).as(name).isEqualTo(expected.path("error").asText());
                    if (expected.has("remaining")) {
                        assertThat(due - PaymentStateCalculator.committed(rows)).as(name)
                                .isEqualTo(expected.path("remaining").asLong());
                        assertThat(e.getUserMessage()).as(name).contains("reste dû : "
                                + XafAmounts.group(expected.path("remaining").asLong()) + " FCFA");
                    }
                    continue;
                }
                assertThat(e).as(name).isNull();
                rows.add(added);
                // the server stores afterChange under the lock, then serves the stored state through view
                served = PaymentStateCalculator.view(invoice, credited,
                        PaymentStateCalculator.afterChange(due, rows, today, stored), rows, today);
            } else {
                // a change already stored (storedState = before it), or a read of a never-computed invoice
                served = PaymentStateCalculator.view(invoice, credited, stored == null ? null
                        : PaymentStateCalculator.afterChange(due, rows, today, stored), rows, today);
            }
            assertThat(served.state()).as(name).isEqualTo(PaymentState.valueOf(expected.path("state").asText()));
            assertThat(served.amountPaid()).as(name).isEqualTo(expected.path("amountPaid").asLong());
            assertThat(served.amountDue()).as(name).isEqualTo(expected.path("amountDue").asLong());
        }
        assertThat(count).isGreaterThanOrEqualTo(15);

        int jobs = 0;
        for (JsonNode c : contract.path("jobCases")) {
            jobs++;
            JsonNode in = c.path("input");
            List<InvoicePayment> rows = new ArrayList<>();
            in.path("rows").forEach(r -> rows.add(row(r)));
            boolean marked = PaymentStateCalculator.markedByJob(stored(in), rows,
                    LocalDate.parse(in.path("today").asText()));
            assertThat(marked).as(c.path("name").asText()).isEqualTo(c.path("expected").path("marked").asBoolean());
            PaymentState after = marked ? PaymentState.OVERDUE : stored(in);
            PaymentState expectedState = PaymentState.valueOf(c.path("expected").path("state").asText());
            if (after != null) {
                assertThat(after).as(c.path("name").asText()).isEqualTo(expectedState);
            }
        }
        assertThat(jobs).isGreaterThanOrEqualTo(5);
    }

    private static PaymentState stored(JsonNode in) {
        return in.hasNonNull("storedState") ? PaymentState.valueOf(in.path("storedState").asText()) : null;
    }

    private static InvoicePayment row(JsonNode r) {
        LocalDate dueDate = r.hasNonNull("dueDate") ? LocalDate.parse(r.path("dueDate").asText()) : null;
        Instant paidAt = r.hasNonNull("paidAt")
                ? LocalDate.parse(r.path("paidAt").asText()).atTime(12, 0).atZone(KeevoZones.WAT).toInstant() : null;
        return new InvoicePayment(UUID.randomUUID(), null, r.path("amount").asLong(), dueDate, paidAt, null, null,
                false);
    }

    // ── calculator ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("after a payment change: never becomes OVERDUE itself (the job does); stays OVERDUE while still late, "
            + "cleared when no instalment is late")
    void afterChange() {
        List<InvoicePayment> late = List.of(paid(30, "2026-11-01"), due(70, "2026-11-01"));
        assertThat(PaymentStateCalculator.afterChange(100, late, TODAY, PaymentState.PARTIAL))
                .isEqualTo(PaymentState.PARTIAL);
        assertThat(PaymentStateCalculator.afterChange(100, List.of(due(100, "2026-11-01")), TODAY, null))
                .isEqualTo(PaymentState.UNPAID);
        assertThat(PaymentStateCalculator.afterChange(100, late, TODAY, PaymentState.OVERDUE))
                .isEqualTo(PaymentState.OVERDUE);
        assertThat(PaymentStateCalculator.afterChange(100, List.of(paid(30, "2026-11-01"), due(70, "2026-12-01")),
                TODAY, PaymentState.OVERDUE)).isEqualTo(PaymentState.PARTIAL);
    }

    @Test
    @DisplayName("order PAID > OVERDUE > PARTIAL > UNPAID; due today is not overdue")
    void stateOrder() {
        assertThat(PaymentStateCalculator.state(100, List.of(paid(100, "2026-11-01"), due(1, "2026-10-01")), TODAY))
                .isEqualTo(PaymentState.PAID);
        assertThat(PaymentStateCalculator.state(100, List.of(paid(50, "2026-11-01"), due(50, "2026-11-05")), TODAY))
                .isEqualTo(PaymentState.OVERDUE);
        assertThat(PaymentStateCalculator.state(100, List.of(paid(50, "2026-11-01"), due(50, "2026-11-06")), TODAY))
                .isEqualTo(PaymentState.PARTIAL);
        assertThat(PaymentStateCalculator.state(100, List.of(), TODAY)).isEqualTo(PaymentState.UNPAID);
        assertThat(PaymentStateCalculator.amountDue(100, 150)).isZero();
    }

    @Test
    @DisplayName("overpayment: French message with the rest due; a non-positive amount is a validation error")
    void overpayment() {
        assertThatThrownBy(() -> PaymentStateCalculator.assertWithinDue(100_000, List.of(paid(80_000, "2026-11-01")),
                30_000)).isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OVERPAYMENT);
                    assertThat(e.getUserMessage()).isEqualTo(
                            "Le montant dépasse ce qui reste dû sur la facture (reste dû : 20\u202F000 FCFA)");
                });
        assertThatThrownBy(() -> PaymentStateCalculator.assertWithinDue(100, List.of(), 0))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        PaymentStateCalculator.assertWithinDue(100_000, List.of(paid(80_000, "2026-11-01")), 20_000);
        assertThat(XafAmounts.group(1_234_567)).isEqualTo("1\u202F234\u202F567");
    }

    @Test
    @DisplayName("read view: legacy PAID → one legacy payment; legacy ISSUED → UNPAID; stored state wins; draft / "
            + "cancelled / quote → no state")
    void view() {
        Invoice issued = issued(100_000);
        Invoice legacyPaid = issued.markedPaid(NOW, "Espèces", NOW);
        var v = PaymentStateCalculator.view(legacyPaid, 0, null, List.of(), TODAY);
        assertThat(v.state()).isEqualTo(PaymentState.PAID);
        assertThat(v.stateLabel()).isEqualTo("Payée");
        assertThat(v.amountPaid()).isEqualTo(100_000);
        assertThat(v.payments()).singleElement().satisfies(p -> {
            assertThat(p.legacy()).isTrue();
            assertThat(p.id()).isNull();
            assertThat(p.amount()).isEqualTo(100_000);
            assertThat(p.paidAt()).isEqualTo(NOW);
            assertThat(p.method()).isEqualTo("Espèces");
        });
        assertThat(PaymentStateCalculator.view(issued, 0, null, List.of(), TODAY).state())
                .isEqualTo(PaymentState.UNPAID);
        // never stored: overdue only once the daily job marked it
        assertThat(PaymentStateCalculator.view(issued, 0, null, List.of(due(100_000, "2026-11-01")), TODAY).state())
                .isEqualTo(PaymentState.UNPAID);
        assertThat(PaymentStateCalculator.view(issued, 0, PaymentState.OVERDUE, List.of(due(100_000, "2026-11-01")),
                TODAY).state()).isEqualTo(PaymentState.OVERDUE);
        assertThat(PaymentStateCalculator.view(issued, 0, PaymentState.PARTIAL, List.of(paid(1, "2026-11-01")), TODAY)
                .state()).isEqualTo(PaymentState.PARTIAL);
        Invoice draft = Invoice.draft(UUID.randomUUID(), CLIENT,
                List.of(new LineInput("X", BigDecimal.ONE, 1000, VatTreatment.STANDARD)), SELLER, null, NOW);
        var d = PaymentStateCalculator.view(draft, 0, null, List.of(due(500, "2026-12-01")), TODAY);
        assertThat(d.state()).isNull();
        assertThat(d.stateLabel()).isNull();
        assertThat(d.payments()).hasSize(1);
        assertThat(PaymentStateCalculator.view(issued.cancel("x", NOW), 0, null, List.of(), TODAY).state()).isNull();
        assertThat(PaymentStateCalculator.view(Invoice.quote(UUID.randomUUID(), CLIENT,
                List.of(new LineInput("X", BigDecimal.ONE, 1000, VatTreatment.STANDARD)), SELLER, null, NOW), 0, null,
                List.of(), TODAY).state()).isNull();
    }

    // ── invoice rules ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("collectable: ISSUED invoice only; schedulable: DRAFT or ISSUED; quote → « Un devis doit être "
            + "converti en facture »")
    void guards() {
        Invoice issued = issued(1000);
        issued.assertCollectable();
        issued.assertSchedulable();
        Invoice draft = Invoice.draft(UUID.randomUUID(), CLIENT,
                List.of(new LineInput("X", BigDecimal.ONE, 1000, VatTreatment.STANDARD)), SELLER, null, NOW);
        draft.assertSchedulable();
        assertThatThrownBy(draft::assertCollectable).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_INVALID_TRANSITION));
        Invoice paidInvoice = issued.markedPaid(NOW, null, NOW);
        assertThatThrownBy(paidInvoice::assertSchedulable).isInstanceOf(DomainException.class);
        Invoice quote = Invoice.quote(UUID.randomUUID(), CLIENT,
                List.of(new LineInput("X", BigDecimal.ONE, 1000, VatTreatment.STANDARD)), SELLER, null, NOW);
        assertThatThrownBy(quote::assertSchedulable).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getUserMessage()).isEqualTo(Invoice.QUOTE_MUST_BE_CONVERTED));
    }

    @Test
    @DisplayName("payment day: future / before issue refused; earlier day = noon WAT; markedPaid keeps the given date")
    void paymentDay() {
        Invoice issued = issued(1000);
        assertThatThrownBy(() -> issued.paymentInstant(LocalDate.parse("2026-11-07"), NOW))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_PAYMENT_DATE_INVALID));
        assertThatThrownBy(() -> issued.paymentInstant(LocalDate.parse("2026-10-31"), NOW))
                .isInstanceOf(DomainException.class);
        assertThat(issued.paymentInstant(LocalDate.parse("2026-11-03"), NOW))
                .isEqualTo(Instant.parse("2026-11-03T11:00:00Z"));
        assertThat(issued.paymentInstant(null, NOW)).isEqualTo(NOW);
        Invoice paid = issued.markedPaid(Instant.parse("2026-11-03T11:00:00Z"), " Espèces ", NOW);
        assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paid.paidAt()).isEqualTo(Instant.parse("2026-11-03T11:00:00Z"));
        assertThat(paid.paymentMethod()).isEqualTo("Espèces");
    }

    @Test
    @DisplayName("cancel: refused with a payment received (French), allowed without")
    void cancelWithPayments() {
        Invoice issued = issued(1000);
        assertThatThrownBy(() -> issued.cancel("Erreur", NOW, false, true))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_INVALID_TRANSITION);
                    assertThat(e.getUserMessage())
                            .isEqualTo("Des paiements sont enregistrés sur cette facture : établissez un avoir");
                });
        assertThat(issued.cancel("Erreur", NOW, false, false).status()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    @DisplayName("replay comparison: same creation matches (even once settled); another content does not")
    void sameCreation() {
        UUID invoiceId = UUID.randomUUID();
        InvoicePayment scheduled = InvoicePayment.scheduled(UUID.randomUUID(), invoiceId, 500,
                LocalDate.parse("2026-12-01"), null, " note ");
        assertThat(scheduled.sameCreation(invoiceId, 500, null, LocalDate.parse("2026-12-01"), "x", "note")).isTrue();
        assertThat(scheduled.settled(NOW, "Espèces").sameCreation(invoiceId, 500, null, LocalDate.parse("2026-12-01"),
                null, "note")).isTrue();
        assertThat(scheduled.sameCreation(invoiceId, 600, null, LocalDate.parse("2026-12-01"), null, "note")).isFalse();
        assertThat(scheduled.sameCreation(UUID.randomUUID(), 500, null, LocalDate.parse("2026-12-01"), null, "note"))
                .isFalse();
        InvoicePayment received = InvoicePayment.received(UUID.randomUUID(), invoiceId, 500,
                Instant.parse("2026-11-03T11:00:00Z"), "Espèces", null);
        assertThat(received.sameCreation(invoiceId, 500, LocalDate.parse("2026-11-03"), null, "Espèces", null))
                .isTrue();
        assertThat(received.sameCreation(invoiceId, 500, LocalDate.parse("2026-11-04"), null, "Espèces", null))
                .isFalse();
        assertThat(received.sameCreation(invoiceId, 500, LocalDate.parse("2026-11-03"), null, "MoMo", null)).isFalse();
    }
}
