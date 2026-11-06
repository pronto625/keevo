package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InvoiceQueryUseCase — credits and collections of a page in constant queries (Story 20.4)")
class InvoiceQueryUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private final CreditNoteRepository creditNotes = mock(CreditNoteRepository.class);
    private final InvoicePaymentRepository payments = mock(InvoicePaymentRepository.class);
    private final InvoiceQueryUseCase queries = new InvoiceQueryUseCase(mock(InvoiceRepository.class), creditNotes,
            payments, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Invoice invoice() {
        InvoicingSettings seller = new InvoicingSettings("Boutique", null, null, null, null, false,
                new BigDecimal("19.25"), true, null, null);
        return Invoice.draft(UUID.randomUUID(), new ClientInfo(null, "Client", null, null, null),
                List.of(new LineInput("X", BigDecimal.ONE, 1_000, VatTreatment.STANDARD)), seller, null, NOW)
                .issue("FAC-2026-000001", java.time.LocalDate.parse("2026-11-06"), seller, NOW);
    }

    @Test
    @DisplayName("a page of 3 invoices, 2 credited: one summaries query, one batched lines query, one ledgers query")
    void batched() {
        Invoice a = invoice();
        Invoice b = invoice();
        Invoice c = invoice();
        when(creditNotes.summaries(any())).thenReturn(Map.of(
                a.id(), List.of(new CreditNoteRepository.Summary(UUID.randomUUID(), "AV-1", NOW, 100, "x")),
                b.id(), List.of(new CreditNoteRepository.Summary(UUID.randomUUID(), "AV-2", NOW, 200, "y"))));
        when(creditNotes.linesOfInvoices(any())).thenReturn(Map.of());
        when(payments.ledgers(any())).thenReturn(Map.of());

        var credits = queries.credits(List.of(a, b, c));
        var collections = queries.collections(List.of(a, b, c), credits);

        assertThat(credits).containsOnlyKeys(a.id(), b.id());
        verify(creditNotes, times(1)).linesOfInvoices(Set.copyOf(List.of(a.id(), b.id())));
        verify(creditNotes, never()).linesOfInvoice(any());
        verify(payments, times(1)).ledgers(any());
        assertThat(collections.get(b.id()).amountDue()).isEqualTo(800);
        assertThat(collections.get(c.id()).state().name()).isEqualTo("UNPAID");
    }
}
