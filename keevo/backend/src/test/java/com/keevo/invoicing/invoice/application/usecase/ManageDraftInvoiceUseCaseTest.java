package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.event.catalog.invoicing.InvoiceCreatedEvent;
import com.keevo.shared.event.catalog.invoicing.QuoteCreatedEvent;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ManageDraftInvoiceUseCase — creation with the device id (Story 15.5b)")
class ManageDraftInvoiceUseCaseTest {

    private static final String TENANT = "kv_test01";
    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoicingSettingsRepository settings = mock(InvoicingSettingsRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final InvoiceClientResolver clients = mock(InvoiceClientResolver.class);
    private final com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository payments =
            mock(com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository.class);
    private final DocumentStoreResolver stores = mock(DocumentStoreResolver.class);
    private ManageDraftInvoiceUseCase useCase;
    private static final UUID STORE_B = UUID.randomUUID();

    private final UUID id = UUID.randomUUID();
    private final ClientInfo client = new ClientInfo(null, "Société Ndolo", null, null, null);
    private final ManageDraftInvoiceUseCase.DraftContent content = new ManageDraftInvoiceUseCase.DraftContent(client,
            List.of(new LineInput("Riz", BigDecimal.ONE, 1000, VatTreatment.STANDARD)));

    @BeforeEach
    void setUp() {
        useCase = new ManageDraftInvoiceUseCase(invoices, settings, events, Clock.fixed(NOW, ZoneOffset.UTC), clients,
                payments, null, stores);
        when(settings.find()).thenReturn(Optional.empty());
        when(clients.resolve(any(), eq(TENANT))).thenReturn(client);
    }

    private Invoice existing() {
        return Invoice.draft(id, client, content.lines(), InvoicingSettings.defaults(), null, NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("an invoice already has the id (even a deleted draft): returned, nothing written or published")
    void alreadyThere() {
        Invoice there = existing();
        when(invoices.findAnyById(id)).thenReturn(Optional.of(there));

        assertThat(useCase.create(id, content, null, TENANT)).isSameAs(there);
        verify(invoices, never()).insertIfAbsent(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("concurrent creation of the same id lost the race: the winner's invoice is returned, no PK error, "
            + "no event")
    void lostTheRace() {
        Invoice winner = existing();
        when(invoices.findAnyById(id)).thenReturn(Optional.empty(), Optional.of(winner));
        when(invoices.insertIfAbsent(any())).thenReturn(false);

        assertThat(useCase.create(id, content, null, TENANT)).isSameAs(winner);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("new id: inserted with that id, event published")
    void inserted() {
        when(invoices.findAnyById(id)).thenReturn(Optional.empty());
        when(invoices.insertIfAbsent(any())).thenReturn(true);

        assertThat(useCase.create(id, content, null, TENANT).id()).isEqualTo(id);
        verify(events).publishEvent(any(Object.class));
        verify(invoices, never()).insert(any());
    }

    @Test
    @DisplayName("20.4 — a draft edited below its scheduled instalments → 409 OVERPAYMENT, nothing written")
    void editBelowInstalments() {
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(existing()));
        when(clients.resolve(any(), eq(TENANT), any())).thenReturn(client);
        when(payments.findByInvoice(id)).thenReturn(List.of(com.keevo.invoicing.invoice.domain.model.InvoicePayment
                .scheduled(UUID.randomUUID(), id, 1_500, java.time.LocalDate.parse("2026-12-01"), null, null)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.update(id, content, TENANT))
                .isInstanceOfSatisfying(com.keevo.shared.domain.exception.DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(com.keevo.shared.domain.exception.ErrorCode.OVERPAYMENT);
                    assertThat(e.getUserMessage()).startsWith("Les échéances programmées (1\u202F500 FCFA) dépassent");
                });
        verify(invoices, never()).updateDraft(any());

        when(payments.findByInvoice(id)).thenReturn(List.of(com.keevo.invoicing.invoice.domain.model.InvoicePayment
                .scheduled(UUID.randomUUID(), id, 1_000, java.time.LocalDate.parse("2026-12-01"), null, null)));
        useCase.update(id, content, TENANT);
        verify(invoices).updateDraft(any());
    }

    // ── Story 20.3 — quotes and the optimistic edit check ─────────────────────────────────────────────────────

    private ManageDraftInvoiceUseCase.DraftContent quoteContent(Instant expected) {
        return new ManageDraftInvoiceUseCase.DraftContent(client, content.lines(), InvoiceKind.QUOTE, expected);
    }

    @Test
    @DisplayName("kind QUOTE: a quote is inserted (no number) and QuoteCreatedEvent published, not InvoiceCreatedEvent")
    void createQuote() {
        when(invoices.findAnyById(id)).thenReturn(Optional.empty());
        when(invoices.insertIfAbsent(any())).thenReturn(true);

        Invoice quote = useCase.create(id, quoteContent(null), null, TENANT);

        assertThat(quote.kind()).isEqualTo(InvoiceKind.QUOTE);
        assertThat(quote.number()).isNull();
        verify(events).publishEvent(org.mockito.ArgumentMatchers.isA(QuoteCreatedEvent.class));
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.isA(InvoiceCreatedEvent.class));
    }

    @Test
    @DisplayName("edit with a stale expectedUpdatedAt → OPTIMISTIC_LOCK, nothing written")
    void staleEdit() {
        Invoice stored = Invoice.quote(id, client, content.lines(), InvoicingSettings.defaults(), null,
                Instant.parse("2026-10-31T08:00:00.123456Z"));
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(stored));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.update(id,
                        quoteContent(Instant.parse("2026-10-31T08:00:00.123457Z")), TENANT))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    assertThat(((DomainException) e).getDomainCode()).isEqualTo("OPTIMISTIC_LOCK");
                    assertThat(((DomainException) e).getUserMessage()).isEqualTo(
                            "Ce document a été modifié entre-temps : rechargez-le avant de l'enregistrer.");
                });
        verify(invoices, never()).updateDraft(any());
    }

    @Test
    @DisplayName("edit with the matching expectedUpdatedAt (microsecond precision) or none → applied, kind kept")
    void freshEdit() {
        Instant seen = Instant.parse("2026-10-31T08:00:00.123456Z");
        Invoice stored = Invoice.quote(id, client, content.lines(), InvoicingSettings.defaults(), null, seen);
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(stored));
        when(clients.resolve(any(), eq(TENANT), any())).thenReturn(client);

        Invoice updated = useCase.update(id, quoteContent(seen.plusNanos(400)), TENANT); // same microsecond
        assertThat(updated.kind()).isEqualTo(InvoiceKind.QUOTE);
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        useCase.update(id, content, TENANT);
        verify(invoices, org.mockito.Mockito.times(2)).updateDraft(any());
    }

    @Test
    @DisplayName("an issued invoice stays non-editable (INVOICE_NOT_EDITABLE), with or without a check")
    void issuedNotEditable() {
        Invoice issued = existing().issue("FAC-2026-000001", java.time.LocalDate.of(2026, 10, 31),
                new InvoicingSettings("Awa", null, null, null, null, false, new BigDecimal("19.25"), true, null, null),
                NOW);
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(issued));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.update(id, content, TENANT))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_NOT_EDITABLE"));
        verify(invoices, never()).updateDraft(any());
    }

    @Test
    @DisplayName("kind mismatch: a creation replay of another kind, or a PUT naming another kind → 409 in French; "
            + "a PUT without kind keeps the document's")
    void kindMismatch() {
        Invoice invoice = existing();
        when(invoices.findAnyById(id)).thenReturn(Optional.of(invoice));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.create(id, quoteContent(null), null, TENANT))
                .satisfies(e -> {
                    assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION");
                    assertThat(((DomainException) e).getUserMessage()).contains("facture");
                });
        assertThat(useCase.create(id, content, null, TENANT)).isSameAs(invoice); // V1 replay: same kind

        Invoice quote = Invoice.quote(id, client, content.lines(), InvoicingSettings.defaults(), null, NOW);
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(quote));
        when(clients.resolve(any(), eq(TENANT), any())).thenReturn(client);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.update(id,
                        new ManageDraftInvoiceUseCase.DraftContent(client, content.lines(), InvoiceKind.INVOICE, null),
                        TENANT))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode())
                        .isEqualTo("INVOICE_INVALID_TRANSITION"));
        verify(invoices, never()).updateDraft(any());
        assertThat(useCase.update(id, content, TENANT).kind()).isEqualTo(InvoiceKind.QUOTE);
    }

    @Test
    @DisplayName("Story 20.5 — a due date before today (WAT) is refused at draft and quote save; today is accepted")
    void pastDueDate() {
        ManageDraftInvoiceUseCase.DraftContent past = new ManageDraftInvoiceUseCase.DraftContent(client,
                content.lines(), null, null, new com.keevo.invoicing.invoice.domain.model.InvoiceTerms(0, 0, null,
                false, java.time.LocalDate.of(2026, 10, 30)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.create(null, past, null, TENANT))
                .isInstanceOfSatisfying(com.keevo.shared.domain.exception.DomainException.class, e -> assertThat(
                        e.getDetails()).containsEntry("reason", "La date d'échéance ne peut pas être passée"));
        ManageDraftInvoiceUseCase.DraftContent pastQuote = new ManageDraftInvoiceUseCase.DraftContent(client,
                content.lines(), com.keevo.invoicing.invoice.domain.model.InvoiceKind.QUOTE, null,
                past.terms());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.create(null, pastQuote, null, TENANT))
                .isInstanceOf(com.keevo.shared.domain.exception.DomainException.class);
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(existing()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.update(id, past, TENANT))
                .isInstanceOf(com.keevo.shared.domain.exception.DomainException.class);
        verify(invoices, never()).insert(any());
        verify(invoices, never()).updateDraft(any());

        ManageDraftInvoiceUseCase.DraftContent today = new ManageDraftInvoiceUseCase.DraftContent(client,
                content.lines(), null, null, new com.keevo.invoicing.invoice.domain.model.InvoiceTerms(0, 0, null,
                false, java.time.LocalDate.of(2026, 10, 31)));
        assertThat(useCase.create(null, today, null, TENANT).terms().dueDate())
                .isEqualTo(java.time.LocalDate.of(2026, 10, 31));
    }

    // ── Story 20.14 — the store of a new document ─────────────────────────────────────────────────────

    @Test
    @DisplayName("20.14: a creation is made in the store the resolver decides (requested storeId handed over); an "
            + "edit keeps it")
    void createdInStore() {
        when(invoices.findAnyById(id)).thenReturn(Optional.empty());
        when(invoices.insertIfAbsent(any())).thenReturn(true);
        when(stores.forNewDocument(STORE_B, TENANT)).thenReturn(STORE_B);
        ManageDraftInvoiceUseCase.DraftContent inB = new ManageDraftInvoiceUseCase.DraftContent(client,
                content.lines(), null, null, null, STORE_B);

        Invoice created = useCase.create(id, inB, null, TENANT);

        assertThat(created.storeId()).isEqualTo(STORE_B);
        org.mockito.ArgumentCaptor<Invoice> written = org.mockito.ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).insertIfAbsent(written.capture());
        assertThat(written.getValue().storeId()).isEqualTo(STORE_B);

        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(created));
        when(clients.resolve(any(), eq(TENANT), any())).thenReturn(client);
        assertThat(useCase.update(id, content, TENANT).storeId()).isEqualTo(STORE_B);
    }

    @Test
    @DisplayName("20.14: a re-sent creation returns the first document, the store is not decided again; a refused "
            + "store writes nothing")
    void replayAndRefusal() {
        Invoice there = existing().inStore(STORE_B);
        when(invoices.findAnyById(id)).thenReturn(Optional.of(there));
        assertThat(useCase.create(id, content, null, TENANT).storeId()).isEqualTo(STORE_B);
        verifyNoInteractions(stores);

        when(stores.forNewDocument(any(), eq(TENANT))).thenThrow(
                com.keevo.shared.domain.exception.DomainException.userFacing(
                        com.keevo.shared.domain.exception.ErrorCode.FORBIDDEN, "autre boutique"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.create(null, content, null, TENANT))
                .isInstanceOf(com.keevo.shared.domain.exception.DomainException.class);
        verify(invoices, never()).insert(any());
    }
}
