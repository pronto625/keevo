package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.crm.api.ClientConsentPort;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.EntitlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("ConvertQuoteUseCase.recordMarketingConsent — Story 22.19 (D8)")
class ConvertQuoteConsent2219Test {

    private static final String TENANT = "kv_test01";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", null, null, null, null,
            false, new BigDecimal("19.25"), true, null, null);

    private final CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final ClientConsentPort consent = mock(ClientConsentPort.class);
    private final UUID clientId = UUID.randomUUID();

    private ConvertQuoteUseCase useCase() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new ConvertQuoteUseCase(mock(InvoiceRepository.class), mock(InvoicingSettingsRepository.class),
                mock(InvoiceLifecycleUseCase.class), mock(ApplicationEventPublisher.class), Clock.systemUTC(), null,
                mock(DocumentStoreResolver.class), txManager, capabilities, mock(EntitlementService.class));
    }

    private Invoice invoice() {
        return Invoice.draft(UUID.randomUUID(), new ClientInfo(clientId, "Société Ndolo", null, null, null),
                List.of(new LineInput("Riz", BigDecimal.ONE, 1000, VatTreatment.STANDARD)), SELLER, ACTOR,
                Instant.now());
    }

    @Test
    @DisplayName("through the crm capability, in its own transaction")
    void recorded() {
        when(capabilities.find(ClientConsentPort.class, TENANT)).thenReturn(Optional.of(consent));

        useCase().recordMarketingConsent(invoice(), ACTOR, TENANT);

        verify(consent).giveMarketingConsent(TENANT, clientId, ACTOR);
        verify(txManager).commit(any());
    }

    @Test
    @DisplayName("a crm failure never fails the conversion answer: rolled back and logged only")
    void failureSwallowed() {
        when(capabilities.find(ClientConsentPort.class, TENANT)).thenReturn(Optional.of(consent));
        doThrow(new IllegalStateException("boom")).when(consent).giveMarketingConsent(any(), any(), any());

        assertThatCode(() -> useCase().recordMarketingConsent(invoice(), ACTOR, TENANT)).doesNotThrowAnyException();
        verify(txManager).rollback(any());
    }

    @Test
    @DisplayName("crm off for the tenant: nothing recorded")
    void crmOff() {
        when(capabilities.find(ClientConsentPort.class, TENANT)).thenReturn(Optional.empty());

        useCase().recordMarketingConsent(invoice(), ACTOR, TENANT);

        verifyNoInteractions(consent);
    }
}
