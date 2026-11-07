package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowQueryPort;
import com.keevo.identity.api.TenantSaleFlowQueryPort.TenantSaleFlowView;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoicingSettingsDto;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 20.6 — the derive-from-documents preference: resolution and the additive settings API. */
@DisplayName("Préférence « dériver depuis les documents » (Story 20.6)")
class DeriveFromDocumentsSettingsTest {

    private static final String TENANT = "kv_test01";

    private final TenantSaleFlowQueryPort flows = mock(TenantSaleFlowQueryPort.class);
    private final DeriveFromDocumentsPreference preference = new DeriveFromDocumentsPreference(flows);

    private static InvoicingSettings settings(Boolean derive) {
        return new InvoicingSettings("Boutique", null, null, null, null, false, new BigDecimal("19.25"), true, null,
                null, derive);
    }

    @Test
    @DisplayName("stored=null: DOCUMENT → true, POS → false, no preferences row → false; stored value wins")
    void resolution() {
        when(flows.find(TENANT)).thenReturn(Optional.of(new TenantSaleFlowView(SaleFlow.DOCUMENT, null)));
        assertThat(preference.resolve(settings(null), TENANT)).isTrue();
        assertThat(preference.resolve(settings(false), TENANT)).isFalse();
        when(flows.find(TENANT)).thenReturn(Optional.of(new TenantSaleFlowView(SaleFlow.POS, null)));
        assertThat(preference.resolve(settings(null), TENANT)).isFalse();
        assertThat(preference.resolve(settings(true), TENANT)).isTrue();
        when(flows.find(TENANT)).thenReturn(Optional.empty());
        assertThat(preference.resolve(settings(null), TENANT)).isFalse();
        assertThat(preference.resolve(null, TENANT)).isFalse();
    }

    @Test
    @DisplayName("PUT without the field (V1 body) keeps the stored choice; with it, saves it; response resolved")
    void updateKeepsStoredChoice() {
        InvoicingSettingsRepository repo = mock(InvoicingSettingsRepository.class);
        InvoicingSettingsUseCase useCase = new InvoicingSettingsUseCase(repo, mock(ApplicationEventPublisher.class),
                Clock.systemUTC(), preference);
        when(flows.find(TENANT)).thenReturn(Optional.of(new TenantSaleFlowView(SaleFlow.DOCUMENT, null)));
        when(repo.find()).thenReturn(Optional.of(settings(false)));

        InvoicingSettingsDto v1 = new InvoicingSettingsDto("Boutique", null, null, null, null, false,
                new BigDecimal("19.25"), true, null, null);
        var view = useCase.updateView(v1.toDomain(), UUID.randomUUID(), TENANT);

        ArgumentCaptor<InvoicingSettings> saved = ArgumentCaptor.forClass(InvoicingSettings.class);
        verify(repo).save(saved.capture(), any(), any());
        assertThat(saved.getValue().deriveFromDocuments()).isFalse();
        assertThat(InvoicingSettingsDto.fromView(view).deriveFromDocuments()).isFalse();

        when(repo.find()).thenReturn(Optional.empty());
        var fresh = useCase.updateView(v1.toDomain(), UUID.randomUUID(), TENANT);
        assertThat(fresh.settings().deriveFromDocuments()).isNull(); // never chosen: stays unset
        assertThat(fresh.deriveFromDocuments()).isTrue(); // resolved from the DOCUMENT flow
    }
}
