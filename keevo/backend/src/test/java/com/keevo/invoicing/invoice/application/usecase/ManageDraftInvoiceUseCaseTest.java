package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
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
    private ManageDraftInvoiceUseCase useCase;

    private final UUID id = UUID.randomUUID();
    private final ClientInfo client = new ClientInfo(null, "Société Ndolo", null, null, null);
    private final ManageDraftInvoiceUseCase.DraftContent content = new ManageDraftInvoiceUseCase.DraftContent(client,
            List.of(new LineInput("Riz", BigDecimal.ONE, 1000, VatTreatment.STANDARD)));

    @BeforeEach
    void setUp() {
        useCase = new ManageDraftInvoiceUseCase(invoices, settings, events, Clock.fixed(NOW, ZoneOffset.UTC), clients);
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
}
