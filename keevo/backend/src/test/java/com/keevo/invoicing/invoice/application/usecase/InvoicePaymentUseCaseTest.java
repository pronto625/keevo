package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.InvoicePaidEvent;
import com.keevo.shared.event.catalog.invoicing.InvoicePaymentRecordedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InvoicePaymentUseCase — deposit, instalments, settlement, overpayment, replay (Story 20.4)")
class InvoicePaymentUseCaseTest {

    private static final String TENANT = "kv_test01";
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", null, null, null, null,
            false, new BigDecimal("19.25"), true, null, null);

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoicePaymentRepository payments = mock(InvoicePaymentRepository.class);
    private final CreditNoteRepository creditNotes = mock(CreditNoteRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private InvoicePaymentUseCase useCase;

    private final UUID invoiceId = UUID.randomUUID();
    private final List<InvoicePayment> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        useCase = new InvoicePaymentUseCase(invoices, payments, creditNotes, events, Clock.fixed(NOW, ZoneOffset.UTC));
        when(creditNotes.summaries(any())).thenReturn(Map.of());
        when(payments.findByInvoice(invoiceId)).thenAnswer(a -> List.copyOf(stored));
        when(payments.insertIfAbsent(any(), any(), any())).thenAnswer(a -> stored.add(a.getArgument(0)));
        when(payments.findById(any())).thenAnswer(a -> stored.stream()
                .filter(p -> p.id().equals(a.getArgument(0))).findFirst());
    }

    private Invoice draft() {
        return Invoice.draft(invoiceId, new ClientInfo(null, "Société Ndolo", null, null, null),
                List.of(new LineInput("Prestation", BigDecimal.ONE, 100_000, VatTreatment.STANDARD)), SELLER, ACTOR,
                NOW.minusSeconds(86_400 * 5));
    }

    private Invoice issued() {
        return draft().issue("FAC-2026-000012", LocalDate.parse("2026-11-01"), SELLER, NOW.minusSeconds(86_400 * 5));
    }

    private void locked(Invoice invoice) {
        when(invoices.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(invoice));
        when(invoices.findById(invoiceId)).thenReturn(Optional.of(invoice));
    }

    private static InvoicePaymentUseCase.NewPayment received(UUID id, long amount) {
        return new InvoicePaymentUseCase.NewPayment(id, amount, TODAY, null, "Mobile Money", null);
    }

    @Test
    @DisplayName("deposit 30 000 on 100 000: row stored, state PARTIAL, status untouched, audit RECEIVED")
    void deposit() {
        locked(issued());
        InvoicePaymentUseCase.Result r = useCase.add(invoiceId, received(UUID.randomUUID(), 30_000), ACTOR, TENANT);

        assertThat(r.created()).isTrue();
        assertThat(stored).singleElement().satisfies(p -> {
            assertThat(p.amount()).isEqualTo(30_000);
            assertThat(p.paidAt()).isEqualTo(NOW);
        });
        verify(payments).storeState(invoiceId, PaymentState.PARTIAL, NOW);
        verify(invoices, never()).markPaid(any());
        ArgumentCaptor<InvoicePaymentRecordedEvent> e = ArgumentCaptor.forClass(InvoicePaymentRecordedEvent.class);
        verify(events).publishEvent(e.capture());
        assertThat(e.getValue().kind()).isEqualTo(InvoicePaymentRecordedEvent.Kind.RECEIVED);
        assertThat(e.getValue().number()).isEqualTo("FAC-2026-000012");
    }

    @Test
    @DisplayName("deposit = total: status PAID with the payment's date and method, InvoicePaidEvent")
    void depositEqualsTotal() {
        locked(issued());
        useCase.add(invoiceId, received(UUID.randomUUID(), 100_000), ACTOR, TENANT);

        ArgumentCaptor<Invoice> paid = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).markPaid(paid.capture());
        assertThat(paid.getValue().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(paid.getValue().paidAt()).isEqualTo(NOW);
        assertThat(paid.getValue().paymentMethod()).isEqualTo("Mobile Money");
        verify(payments).storeState(invoiceId, PaymentState.PAID, NOW);
        verify(events).publishEvent(any(InvoicePaidEvent.class));
    }

    @Test
    @DisplayName("overpayment and schedule exceeding the due: 409 OVERPAYMENT, nothing written")
    void overpayment() {
        locked(issued());
        useCase.add(invoiceId, received(UUID.randomUUID(), 80_000), ACTOR, TENANT);
        assertThatThrownBy(() -> useCase.add(invoiceId, received(UUID.randomUUID(), 30_000), ACTOR, TENANT))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OVERPAYMENT));
        assertThatThrownBy(() -> useCase.add(invoiceId, new InvoicePaymentUseCase.NewPayment(UUID.randomUUID(),
                30_000, null, TODAY.plusDays(30), null, null), ACTOR, TENANT)).isInstanceOf(DomainException.class);
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("credit note reduces the due: 80 000 paid on 100 000 credited 20 000 → PAID")
    void creditNote() {
        locked(issued());
        when(creditNotes.summaries(any())).thenReturn(Map.of(invoiceId, List.of(new CreditNoteRepository.Summary(
                UUID.randomUUID(), "AV-2026-000001", NOW, 20_000, "Erreur"))));
        useCase.add(invoiceId, received(UUID.randomUUID(), 80_000), ACTOR, TENANT);
        verify(payments).storeState(invoiceId, PaymentState.PAID, NOW);
        verify(invoices).markPaid(any());
    }

    @Test
    @DisplayName("instalments on a draft: stored, no state, updated_at moved; a received payment on a draft refused")
    void draftSchedule() {
        locked(draft());
        useCase.add(invoiceId, new InvoicePaymentUseCase.NewPayment(UUID.randomUUID(), 50_000, null,
                TODAY.plusDays(30), null, null), ACTOR, TENANT);
        useCase.add(invoiceId, new InvoicePaymentUseCase.NewPayment(UUID.randomUUID(), 50_000, null,
                TODAY.plusDays(60), null, null), ACTOR, TENANT);
        assertThat(stored).hasSize(2);
        verify(payments, never()).storeState(any(), any(), any());
        verify(payments, org.mockito.Mockito.times(2)).touch(invoiceId, NOW);
        assertThatThrownBy(() -> useCase.add(invoiceId, received(UUID.randomUUID(), 1_000), ACTOR, TENANT))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_INVALID_TRANSITION));
    }

    @Test
    @DisplayName("exactly one of paidAt / dueDate, else VALIDATION_ERROR; quote → INVOICE_INVALID_TRANSITION")
    void validation() {
        locked(issued());
        assertThatThrownBy(() -> useCase.add(invoiceId, new InvoicePaymentUseCase.NewPayment(null, 1_000, TODAY,
                TODAY, null, null), ACTOR, TENANT)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> useCase.add(invoiceId, new InvoicePaymentUseCase.NewPayment(null, 1_000, null,
                null, null, null), ACTOR, TENANT)).isInstanceOf(DomainException.class);
        Invoice quote = Invoice.quote(invoiceId, new ClientInfo(null, "X", null, null, null),
                List.of(new LineInput("X", BigDecimal.ONE, 1_000, VatTreatment.STANDARD)), SELLER, ACTOR, NOW);
        locked(quote);
        assertThatThrownBy(() -> useCase.add(invoiceId, received(null, 1_000), ACTOR, TENANT))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_INVALID_TRANSITION);
                    assertThat(e.getUserMessage()).isEqualTo("Un devis doit être converti en facture");
                });
    }

    @Test
    @DisplayName("replay: same paymentId + content → not created, one row; another content → VALIDATION_ERROR")
    void replay() {
        locked(issued());
        UUID id = UUID.randomUUID();
        useCase.add(invoiceId, received(id, 30_000), ACTOR, TENANT);
        InvoicePaymentUseCase.Result again = useCase.add(invoiceId, received(id, 30_000), ACTOR, TENANT);
        assertThat(again.created()).isFalse();
        assertThat(stored).hasSize(1);
        assertThatThrownBy(() -> useCase.add(invoiceId, received(id, 31_000), ACTOR, TENANT))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("settle an overdue instalment: row paid, state recomputed; settled again → no-op; unknown → 404")
    void settle() {
        locked(issued());
        UUID late = UUID.randomUUID();
        stored.add(InvoicePayment.scheduled(late, invoiceId, 50_000, TODAY.minusDays(2), null, null));
        stored.add(InvoicePayment.scheduled(UUID.randomUUID(), invoiceId, 50_000, TODAY.plusDays(20), null, null));
        org.mockito.Mockito.doAnswer(a -> {
            stored.replaceAll(p -> p.id().equals(late) ? p.settled(a.getArgument(1), a.getArgument(2)) : p);
            return null;
        }).when(payments).settle(eq(late), any(), any(), any());

        InvoicePaymentUseCase.Result r = useCase.settle(invoiceId, late, null, "Espèces", ACTOR, TENANT);
        assertThat(r.created()).isTrue();
        verify(payments).storeState(invoiceId, PaymentState.PARTIAL, NOW);
        assertThat(useCase.settle(invoiceId, late, null, null, ACTOR, TENANT).created()).isFalse();
        assertThatThrownBy(() -> useCase.settle(invoiceId, UUID.randomUUID(), null, null, ACTOR, TENANT))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_NOT_FOUND));
    }

    @Test
    @DisplayName("V1 pay = settle all: instalments settled, balance added, status PAID")
    void settleAll() {
        Invoice issued = issued();
        stored.add(InvoicePayment.received(UUID.randomUUID(), invoiceId, 30_000, NOW.minusSeconds(3600), null, null));
        stored.add(InvoicePayment.scheduled(UUID.randomUUID(), invoiceId, 40_000, TODAY.plusDays(10), null, null));
        when(payments.settleUnpaid(eq(invoiceId), any(), any(), any())).thenAnswer(a -> {
            stored.replaceAll(p -> p.isPaid() ? p : p.settled(a.getArgument(1), a.getArgument(2)));
            return 1;
        });

        Invoice paid = useCase.settleAll(issued, "Espèces", null, ACTOR, TENANT);

        assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(stored).hasSize(3).allMatch(InvoicePayment::isPaid);
        assertThat(stored.get(2).amount()).isEqualTo(30_000);
        verify(payments).storeState(invoiceId, PaymentState.PAID, NOW);
        verify(events).publishEvent(any(InvoicePaidEvent.class));
    }

    @Test
    @DisplayName("an instalment due before today → VALIDATION_ERROR (French), nothing written")
    void pastDueDate() {
        locked(issued());
        assertThatThrownBy(() -> useCase.add(invoiceId, new InvoicePaymentUseCase.NewPayment(UUID.randomUUID(),
                10_000, null, TODAY.minusDays(1), null, null), ACTOR, TENANT))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getDetails().get("reason")).isEqualTo("La date d'échéance ne peut pas être passée");
                });
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("credit note exceeding what remains after the payments received → 409 French, nothing trimmed")
    void creditBelowPaid() {
        Invoice issued = issued();
        stored.add(InvoicePayment.received(UUID.randomUUID(), invoiceId, 90_000, NOW, null, null));
        assertThatThrownBy(() -> useCase.beforeCredit(issued, 20_000))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVOICE_INVALID_TRANSITION);
                    assertThat(e.getUserMessage())
                            .isEqualTo("Le montant déjà encaissé dépasse le reste dû après cet avoir.");
                });
        verify(payments, never()).delete(any());
    }

    @Test
    @DisplayName("credit note: unpaid instalments trimmed latest due date first until every row fits")
    void creditTrimsInstalments() {
        Invoice issued = issued();
        UUID early = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        stored.add(InvoicePayment.received(UUID.randomUUID(), invoiceId, 20_000, NOW, null, null));
        stored.add(InvoicePayment.scheduled(early, invoiceId, 40_000, TODAY.plusDays(10), null, null));
        stored.add(InvoicePayment.scheduled(late, invoiceId, 40_000, TODAY.plusDays(40), null, null));

        useCase.beforeCredit(issued, 50_000); // new due 50 000: 50 000 committed too many

        verify(payments).delete(late);
        verify(payments).updateAmount(early, 30_000, NOW);
        // a PAID invoice (its credit note is a refund) is left as it is
        useCase.beforeCredit(issued.markedPaid(NOW, null, NOW), 100_000);
        verify(payments, org.mockito.Mockito.times(1)).delete(any());
    }

    @Test
    @DisplayName("after a credit note: state recomputed (fully covered → PAID), legal status never changed")
    void refreshAfterCredit() {
        Invoice issued = issued();
        stored.add(InvoicePayment.received(UUID.randomUUID(), invoiceId, 80_000, NOW, null, null));
        when(creditNotes.summaries(any())).thenReturn(Map.of(invoiceId, List.of(new CreditNoteRepository.Summary(
                UUID.randomUUID(), "AV-2026-000001", NOW, 20_000, "Erreur"))));
        useCase.refreshAfterCredit(issued, ACTOR, TENANT);
        verify(payments).storeState(invoiceId, PaymentState.PAID, NOW);
        verify(invoices, never()).markPaid(any());
    }

    @Test
    @DisplayName("settle refuses to record more than owed (invariant broken → OVERPAYMENT)")
    void settleInvariant() {
        locked(issued());
        UUID instalment = UUID.randomUUID();
        stored.add(InvoicePayment.received(UUID.randomUUID(), invoiceId, 80_000, NOW, null, null));
        stored.add(InvoicePayment.scheduled(instalment, invoiceId, 30_000, TODAY.plusDays(5), null, null));
        assertThatThrownBy(() -> useCase.settle(invoiceId, instalment, null, null, ACTOR, TENANT))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OVERPAYMENT));
        assertThatThrownBy(() -> useCase.settleAll(issued(), null, null, ACTOR, TENANT))
                .isInstanceOf(DomainException.class);
        verify(payments, never()).settle(any(), any(), any(), any());
        verify(payments, never()).settleUnpaid(any(), any(), any(), any());
    }
}
