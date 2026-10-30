package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.invoicing.invoice.domain.port.out.SaleInvoiceStateRepository;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 14.4 — a sale whose invoice content the domain refuses is not invoiced and not retried. */
class InvoiceSaleUseCaseTest {

    @Test
    @DisplayName("content refused by the domain after the mapper accepted it → NOT_INVOICEABLE, nothing written")
    void domainRefusal_notInvoiceable() {
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        InvoicingSettingsRepository settings = mock(InvoicingSettingsRepository.class);
        InvoiceLifecycleUseCase lifecycle = mock(InvoiceLifecycleUseCase.class);
        InvoiceClientResolver clients = mock(InvoiceClientResolver.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        SaleInvoiceStateRepository states = mock(SaleInvoiceStateRepository.class);
        when(states.lock(any())).thenReturn(new SaleInvoiceStateRepository.State(null, null));
        when(invoices.existsBySourceSale(any())).thenReturn(false);
        when(settings.find()).thenReturn(Optional.empty());
        when(clients.saleClient(any(), any())).thenReturn(Optional.empty());
        InvoiceSaleUseCase useCase = new InvoiceSaleUseCase(invoices, settings, lifecycle, clients, events,
                Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC), states);
        SaleCompletedEvent sale = new SaleCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), "kv_abc123",
                UUID.randomUUID(), 1000, 0, "[]", Instant.now(), null, "CASH",
                List.of(new SaleCompletedEvent.Line("Savon", 1000, 1)));

        // the mapper accepts the sale; the domain then refuses the draft (e.g. an amount out of its limits)
        try (MockedStatic<Invoice> domain = mockStatic(Invoice.class, CALLS_REAL_METHODS)) {
            domain.when(() -> Invoice.draft(any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Amount too large"));

            assertThat(useCase.invoice(sale)).isEqualTo(InvoiceSaleUseCase.Outcome.NOT_INVOICEABLE);
        }

        verify(invoices, org.mockito.Mockito.never()).insert(any());
        verifyNoInteractions(lifecycle, events);
    }
}
