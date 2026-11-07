package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.catalog.api.ProductLookupPort;
import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.api.DocumentClientInput;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceTerms;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleState;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 20.6 — the derivation choke point: gates (preference, kind, status, POS bridge, module states), links set
 * through a rebuilt draft whose totals are those of the issue without derivation, nothing written when nothing links.
 */
@DisplayName("DocumentDerivationService — dérivation à l'émission (Story 20.6)")
class DocumentDerivationServiceTest {

    private static final String TENANT = "kv_test01";
    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique Awa SARL", null, null, null, null,
            true, new BigDecimal("19.25"), true, null, null);

    private final CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final DeriveFromDocumentsPreference preference = mock(DeriveFromDocumentsPreference.class);
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final ClientLookupPort crm = mock(ClientLookupPort.class);
    private final ProductLookupPort catalog = mock(ProductLookupPort.class);
    private DocumentDerivationService service;

    private final UUID clientId = UUID.randomUUID();
    private final UUID riceId = UUID.randomUUID();
    private final UUID oilId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DocumentDerivationService(capabilities, entitlements, preference, invoices);
        when(preference.resolve(any(), eq(TENANT))).thenReturn(true);
        when(entitlements.state(eq(TENANT), anyString())).thenReturn(ModuleState.ENABLED);
        when(capabilities.find(ClientLookupPort.class, TENANT)).thenReturn(Optional.of(crm));
        when(capabilities.find(ProductLookupPort.class, TENANT)).thenReturn(Optional.of(catalog));
        when(crm.findOrCreateForDocument(eq(TENANT), any(), eq(ACTOR))).thenReturn(Optional.of(clientId));
        when(catalog.findOrCreateForDocument(eq(TENANT), anyList(), eq(ACTOR))).thenReturn(Map.of(
                "nm:riz 25kg", riceId, "bc:6001234567890", oilId));
    }

    private static Invoice draft(UUID linkedClient, UUID linkedProduct) {
        ClientInfo client = new ClientInfo(linkedClient, "Awa", "NIU-1", "Akwa", "699000001", "RC-1",
                "awa@example.com", null, null);
        return Invoice.draft(UUID.randomUUID(), client, List.of(
                        new LineInput("Riz 25kg", new BigDecimal("2"), 15_000, VatTreatment.STANDARD, linkedProduct,
                                null),
                        new LineInput("Huile", new BigDecimal("3"), 2_500, VatTreatment.STANDARD, null,
                                "6001234567890")),
                new InvoiceTerms(1_000, 500, null, false, null), SELLER, ACTOR, NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("client and lines linked, draft saved, totals identical to an issue without derivation")
    void linksAndKeepsTotals() {
        Invoice draft = draft(null, null);

        Invoice derived = service.derive(draft, SELLER, SELLER, ACTOR, TENANT, NOW);

        assertThat(derived.client().clientId()).isEqualTo(clientId);
        assertThat(derived.client().name()).isEqualTo("Awa");
        assertThat(derived.client().email()).isEqualTo("awa@example.com");
        assertThat(derived.lines()).extracting(l -> l.productId()).containsExactly(riceId, oilId);
        assertThat(derived.lines()).extracting(l -> l.reference()).containsExactly(null, "6001234567890");
        assertThat(derived.totals()).isEqualTo(draft.totals());
        assertThat(derived.terms()).isEqualTo(draft.terms());
        assertThat(derived.issue("FAC-2026-000001", LocalDate.of(2026, 11, 7), SELLER, NOW).totals())
                .isEqualTo(draft.issue("FAC-2026-000001", LocalDate.of(2026, 11, 7), SELLER, NOW).totals());
        verify(invoices).updateDraft(derived);
        ArgumentCaptor<DocumentClientInput> input = ArgumentCaptor.forClass(DocumentClientInput.class);
        verify(crm).findOrCreateForDocument(eq(TENANT), input.capture(), eq(ACTOR));
        assertThat(input.getValue()).isEqualTo(new DocumentClientInput("Awa", "699000001", "awa@example.com", "RC-1",
                "NIU-1", "Akwa"));
        ArgumentCaptor<List<ProductLookupPort.DocumentLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(catalog).findOrCreateForDocument(eq(TENANT), lines.capture(), eq(ACTOR));
        assertThat(lines.getValue()).containsExactly(new ProductLookupPort.DocumentLine("Riz 25kg", null, 15_000),
                new ProductLookupPort.DocumentLine("Huile", "6001234567890", 2_500));
    }

    @Test
    @DisplayName("preference off → nothing called, draft unchanged")
    void preferenceOff() {
        when(preference.resolve(any(), eq(TENANT))).thenReturn(false);
        Invoice draft = draft(null, null);
        assertThat(service.derive(draft, SELLER, SELLER, ACTOR, TENANT, NOW)).isSameAs(draft);
        verifyNoInteractions(capabilities, invoices);
    }

    @Test
    @DisplayName("crm read-only → client skipped; catalog absent → products skipped; nothing linked → nothing written")
    void moduleStates() {
        when(entitlements.state(TENANT, "crm")).thenReturn(ModuleState.READ_ONLY);
        Invoice derived = service.derive(draft(null, null), SELLER, SELLER, ACTOR, TENANT, NOW);
        assertThat(derived.client().clientId()).isNull();
        assertThat(derived.lines()).extracting(l -> l.productId()).containsExactly(riceId, oilId);
        verify(crm, never()).findOrCreateForDocument(any(), any(), any());

        when(capabilities.find(ProductLookupPort.class, TENANT)).thenReturn(Optional.empty());
        Invoice nothing = draft(null, null);
        assertThat(service.derive(nothing, SELLER, SELLER, ACTOR, TENANT, NOW)).isSameAs(nothing);
        verify(invoices, never()).updateDraft(nothing);
    }

    @Test
    @DisplayName("already linked client and line → only the free line is asked; quote and POS-bridged drafts untouched")
    void onlyUnlinkedAndOnlyInvoices() {
        UUID stored = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        Invoice derived = service.derive(draft(stored, product), SELLER, SELLER, ACTOR, TENANT, NOW);
        assertThat(derived.client().clientId()).isEqualTo(stored);
        assertThat(derived.lines()).extracting(l -> l.productId()).containsExactly(product, oilId);
        verify(crm, never()).findOrCreateForDocument(any(), any(), any());

        Invoice quote = Invoice.quote(UUID.randomUUID(), new ClientInfo(null, "Awa", null, null, "699000001"),
                List.of(new LineInput("Riz", BigDecimal.ONE, 100, VatTreatment.STANDARD)), SELLER, ACTOR, NOW);
        assertThat(service.derive(quote, SELLER, SELLER, ACTOR, TENANT, NOW)).isSameAs(quote);
        Invoice issued = draft(null, null).issue("FAC-2026-000009", LocalDate.of(2026, 11, 7), SELLER, NOW);
        assertThat(service.derive(issued, SELLER, SELLER, ACTOR, TENANT, NOW)).isSameAs(issued);
    }

    @Test
    @DisplayName("POS bridge (uncounted issue) never derives; a user's issue does")
    void bridgeNeverDerives() {
        InvoiceRepository repo = mock(InvoiceRepository.class);
        InvoicingSettingsRepository settings = mock(InvoicingSettingsRepository.class);
        CompanyIdentity company = mock(CompanyIdentity.class);
        DocumentDerivationService derivation = mock(DocumentDerivationService.class);
        InvoiceLifecycleUseCase lifecycle = new InvoiceLifecycleUseCase(repo, settings,
                mock(ApplicationEventPublisher.class), company, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository.class),
                mock(UsageQuotaPort.class), mock(InvoicePaymentUseCase.class), derivation);
        when(settings.find()).thenReturn(Optional.of(SELLER));
        when(repo.nextNumber(2026)).thenReturn(1L);
        Invoice bridged = draft(null, null);
        when(repo.findByIdForUpdate(bridged.id())).thenReturn(Optional.of(bridged));

        lifecycle.issue(bridged.id(), ACTOR, TENANT, true);
        verifyNoInteractions(derivation);

        Invoice user = draft(null, null);
        when(repo.findByIdForUpdate(user.id())).thenReturn(Optional.of(user));
        when(derivation.derive(eq(user), eq(SELLER), any(), eq(ACTOR), eq(TENANT), eq(NOW))).thenReturn(user);
        lifecycle.issue(user.id(), ACTOR, TENANT);
        verify(derivation).derive(eq(user), eq(SELLER), any(), eq(ACTOR), eq(TENANT), eq(NOW));
    }
}
