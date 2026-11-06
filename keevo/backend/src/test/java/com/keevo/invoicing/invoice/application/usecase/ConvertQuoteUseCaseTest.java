package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.QuoteConvertedEvent;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ConvertQuoteUseCase — quote → issued invoice, idempotent (Story 20.3)")
class ConvertQuoteUseCaseTest {

    private static final String TENANT = "kv_test01";
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", null, null, null, null,
            false, new BigDecimal("19.25"), true, null, null);

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoicingSettingsRepository settings = mock(InvoicingSettingsRepository.class);
    private final InvoiceLifecycleUseCase lifecycle = mock(InvoiceLifecycleUseCase.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private ConvertQuoteUseCase useCase;

    private final UUID quoteId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();
    private final ClientInfo client = new ClientInfo(null, "Société Ndolo", null, null, null);

    @BeforeEach
    void setUp() {
        useCase = new ConvertQuoteUseCase(invoices, settings, lifecycle, events, Clock.fixed(NOW, ZoneOffset.UTC));
        when(settings.find()).thenReturn(Optional.of(SELLER));
    }

    private Invoice quote() {
        return Invoice.quote(quoteId, client, List.of(new LineInput("Riz", BigDecimal.ONE, 11_925,
                VatTreatment.STANDARD)), SELLER, ACTOR, NOW.minusSeconds(3600));
    }

    private Invoice issued(Invoice draft) {
        return draft.issue("FAC-2026-000042", LocalDate.of(2026, 11, 6), SELLER, NOW);
    }

    /** The invoice inserted by the use case, readable as soon as it is inserted (before the issue). */
    private ArgumentCaptor<Invoice> stashInserted() {
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        when(invoices.insertConversion(captor.capture())).thenReturn(true);
        return captor;
    }

    @Test
    @DisplayName("DRAFT quote: new invoice inserted with the device id, issued by the issue path, quote CONVERTED")
    void converts() {
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote()));
        ArgumentCaptor<Invoice> inserted = stashInserted();
        when(lifecycle.issue(invoiceId, ACTOR, TENANT)).thenAnswer(a -> issued(inserted.getValue()));

        ConvertQuoteUseCase.Conversion c = useCase.convert(quoteId, invoiceId, ACTOR, TENANT);

        assertThat(inserted.getValue().id()).isEqualTo(invoiceId);
        assertThat(inserted.getValue().kind()).isEqualTo(InvoiceKind.INVOICE);
        assertThat(inserted.getValue().sourceQuoteId()).isEqualTo(quoteId);
        assertThat(c.created()).isTrue();
        assertThat(c.invoice().number()).isEqualTo("FAC-2026-000042");
        ArgumentCaptor<Invoice> converted = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).markConverted(converted.capture());
        assertThat(converted.getValue().status()).isEqualTo(InvoiceStatus.CONVERTED);
        assertThat(converted.getValue().convertedInvoiceId()).isEqualTo(invoiceId);
        ArgumentCaptor<QuoteConvertedEvent> event = ArgumentCaptor.forClass(QuoteConvertedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().invoiceNumber()).isEqualTo("FAC-2026-000042");
        assertThat(event.getValue().reference()).startsWith("DV-");
    }

    @Test
    @DisplayName("no device id: a new id is generated")
    void generatedId() {
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote()));
        ArgumentCaptor<Invoice> inserted = stashInserted();
        when(lifecycle.issue(any(), eq(ACTOR), eq(TENANT))).thenAnswer(a -> issued(inserted.getValue()));

        ConvertQuoteUseCase.Conversion c = useCase.convert(quoteId, null, ACTOR, TENANT);

        assertThat(c.invoice().id()).isEqualTo(inserted.getValue().id()).isNotNull();
    }

    @Test
    @DisplayName("replay on a CONVERTED quote: the existing invoice, nothing inserted, issued or counted")
    void replay() {
        Invoice converted = quote().converted(invoiceId, NOW);
        Invoice existing = issued(quote().conversionDraft(invoiceId, SELLER, ACTOR, NOW));
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(converted));
        when(invoices.findById(invoiceId)).thenReturn(Optional.of(existing));

        ConvertQuoteUseCase.Conversion c = useCase.convert(quoteId, UUID.randomUUID(), ACTOR, TENANT);

        assertThat(c.created()).isFalse();
        assertThat(c.invoice()).isSameAs(existing);
        verify(invoices, never()).insertConversion(any());
        verify(invoices, never()).markConverted(any());
        verifyNoInteractions(lifecycle, events);
    }

    @Test
    @DisplayName("refusal of the issue (QUOTA_EXCEEDED) propagates before the quote is touched")
    void quotaExceeded() {
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote()));
        when(invoices.insertConversion(any())).thenReturn(true);
        when(lifecycle.issue(invoiceId, ACTOR, TENANT)).thenThrow(new DomainException(ErrorCode.QUOTA_EXCEEDED, "q"));

        assertThatThrownBy(() -> useCase.convert(quoteId, invoiceId, ACTOR, TENANT))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("QUOTA_EXCEEDED"));
        verify(invoices, never()).markConverted(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("cancelled quote, or an invoice → INVOICE_INVALID_TRANSITION; unknown → INVOICE_NOT_FOUND")
    void refused() {
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote().cancel("non", NOW)));
        assertThatThrownBy(() -> useCase.convert(quoteId, invoiceId, ACTOR, TENANT)).satisfies(e ->
                assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));

        UUID plainId = UUID.randomUUID();
        when(invoices.findByIdForUpdate(plainId)).thenReturn(Optional.of(Invoice.draft(plainId, client, List.of(),
                SELLER, null, NOW)));
        assertThatThrownBy(() -> useCase.convert(plainId, null, ACTOR, TENANT)).satisfies(e ->
                assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION"));

        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.convert(quoteId, null, ACTOR, TENANT)).satisfies(e ->
                assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_NOT_FOUND"));
        verify(invoices, never()).insertConversion(any());
        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("device invoiceId taken by another document (insert answers false, no invoice from this quote) → "
            + "VALIDATION_ERROR in French, nothing issued")
    void idTaken() {
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote()));
        when(invoices.insertConversion(any())).thenReturn(false);
        when(invoices.findBySourceQuote(quoteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.convert(quoteId, invoiceId, ACTOR, TENANT)).satisfies(e -> {
            assertThat(((DomainException) e).getDomainCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(((DomainException) e).getUserMessage()).contains("déjà utilisé");
        });
        verifyNoInteractions(lifecycle, events);
        verify(invoices, never()).markConverted(any());
    }

    @Test
    @DisplayName("an invoice already made from the quote (uq_invoices_source_quote): returned, created=false")
    void sourceQuoteRace() {
        Invoice winner = issued(quote().conversionDraft(UUID.randomUUID(), SELLER, ACTOR, NOW));
        Invoice convertedQuote = quote().converted(winner.id(), NOW);
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote()));
        when(invoices.insertConversion(any())).thenReturn(false);
        when(invoices.findBySourceQuote(quoteId)).thenReturn(Optional.of(winner));
        when(invoices.findById(quoteId)).thenReturn(Optional.of(convertedQuote));

        ConvertQuoteUseCase.Conversion c = useCase.convert(quoteId, invoiceId, ACTOR, TENANT);

        assertThat(c.created()).isFalse();
        assertThat(c.invoice()).isSameAs(winner);
        assertThat(c.quote()).isSameAs(convertedQuote);
        verifyNoInteractions(lifecycle, events);
    }

    @Test
    @DisplayName("VAT settings changed since the quote (totals differ) → 409 with the French reason, nothing written")
    void vatSettingsChanged() {
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote()));
        when(settings.find()).thenReturn(Optional.of(new InvoicingSettings("Boutique Awa SARL", null, null, null, null,
                true, new BigDecimal("19.25"), false, null, null)));

        assertThatThrownBy(() -> useCase.convert(quoteId, invoiceId, ACTOR, TENANT)).satisfies(e -> {
            assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_INVALID_TRANSITION");
            assertThat(((DomainException) e).getUserMessage()).isEqualTo(ConvertQuoteUseCase.VAT_SETTINGS_CHANGED);
        });
        verify(invoices, never()).insertConversion(any());
        verifyNoInteractions(lifecycle, events);
    }

    @Test
    @DisplayName("expectedUpdatedAt: stale → OPTIMISTIC_LOCK (French), nothing written; matching → converted; a replay "
            + "with a stale check still returns the invoice")
    void optimisticCheck() {
        Invoice quote = quote();
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> useCase.convert(quoteId, invoiceId, quote.updatedAt().minusSeconds(1), ACTOR,
                TENANT)).satisfies(e -> {
            assertThat(((DomainException) e).getDomainCode()).isEqualTo("OPTIMISTIC_LOCK");
            assertThat(((DomainException) e).getUserMessage())
                    .isEqualTo("Ce document a été modifié entre-temps : rechargez-le avant de l'enregistrer.");
        });
        verify(invoices, never()).insertConversion(any());

        ArgumentCaptor<Invoice> inserted = stashInserted();
        when(lifecycle.issue(invoiceId, ACTOR, TENANT)).thenAnswer(a -> issued(inserted.getValue()));
        assertThat(useCase.convert(quoteId, invoiceId, quote.updatedAt(), ACTOR, TENANT).created()).isTrue();

        Invoice existing = issued(quote.conversionDraft(invoiceId, SELLER, ACTOR, NOW));
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote.converted(invoiceId, NOW)));
        when(invoices.findById(invoiceId)).thenReturn(Optional.of(existing));
        assertThat(useCase.convert(quoteId, invoiceId, quote.updatedAt(), ACTOR, TENANT).invoice()).isSameAs(existing);
    }

    @Test
    @DisplayName("incomplete quote (no line) → INVOICE_NOT_ISSUABLE « Ce devis est incomplet : … », nothing written")
    void incomplete() {
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(Invoice.quote(quoteId, client, List.of(),
                SELLER, ACTOR, NOW)));
        assertThatThrownBy(() -> useCase.convert(quoteId, invoiceId, ACTOR, TENANT)).satisfies(e -> {
            assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_NOT_ISSUABLE");
            assertThat(((DomainException) e).getUserMessage())
                    .isEqualTo("Ce devis est incomplet : ajoutez au moins une ligne");
        });
        verify(invoices, never()).insertConversion(any());
        verifyNoInteractions(lifecycle);
    }

    @Test
    @DisplayName("Story 20.5 — quote with a due date already passed: 409 French, nothing inserted, no number")
    void pastDueDate() {
        Invoice quote = Invoice.quote(quoteId, client, List.of(new LineInput("Riz", BigDecimal.ONE, 11_925,
                        VatTreatment.STANDARD)),
                new com.keevo.invoicing.invoice.domain.model.InvoiceTerms(0, 0, null, false,
                        LocalDate.of(2026, 11, 5)), SELLER, ACTOR, NOW.minusSeconds(3600));
        when(invoices.findByIdForUpdate(quoteId)).thenReturn(Optional.of(quote));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.convert(quoteId, invoiceId, ACTOR, TENANT))
                .isInstanceOfSatisfying(com.keevo.shared.domain.exception.DomainException.class, e -> {
                    assertThat(e.getErrorCode())
                            .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.INVOICE_INVALID_TRANSITION);
                    assertThat(e.getUserMessage())
                            .isEqualTo("La date d'échéance de ce devis est passée : modifiez-la avant de le convertir.");
                });
        verify(invoices, org.mockito.Mockito.never()).insertConversion(any());
        org.mockito.Mockito.verifyNoInteractions(lifecycle);
    }
}
