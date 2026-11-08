package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePresentationRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import static org.mockito.Mockito.when;

@DisplayName("InvoicePublicLinkUseCase — lifetime per tenant, versions, rotation, legacy Bref links (Story 20.7)")
class InvoicePublicLinkUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final String SCHEMA = "kv_abc123";
    private static final InvoicingSettings SELLER = new InvoicingSettings("Boutique", "Douala", null, null, null,
            false, new BigDecimal("19.25"), true, null, null);

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoicePresentationRepository presentations = mock(InvoicePresentationRepository.class);
    private final LegacyInvoiceLinkRegistry registry = mock(LegacyInvoiceLinkRegistry.class);
    private final CompanyIdentity identity = mock(CompanyIdentity.class);
    private final InvoiceLinkSigner signer = new InvoiceLinkSigner("0123456789abcdef0123456789abcdef-test");
    private final InvoicePublicLinkUseCase links = new InvoicePublicLinkUseCase(invoices, presentations, registry,
            signer, identity, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Invoice issued() {
        return Invoice.draft(UUID.randomUUID(), new ClientInfo(null, "Client", null, null, null),
                List.of(new LineInput("Article", BigDecimal.ONE, 1_000, VatTreatment.STANDARD)), SELLER,
                UUID.randomUUID(), NOW).issue("FAC-2026-000001", LocalDate.of(2026, 11, 7), SELLER, NOW);
    }

    private static InvoicePresentation version(int v, UUID legacy) {
        return new InvoicePresentation(null, null, null, null, v, legacy, legacy == null ? null : "BRF-1A2B3C4D");
    }

    private String token(InvoicePublicLinkUseCase.Link link) {
        return link.url().substring(link.url().indexOf("token=") + 6);
    }

    @Test
    @DisplayName("lifetime: 30 days by default, the tenant's choice (90 for an imported tenant) otherwise")
    void lifetime() {
        Invoice invoice = issued();
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(presentations.find(invoice.id())).thenReturn(Optional.of(version(0, null)));
        when(presentations.preferences()).thenReturn(DocumentPreferences.DEFAULTS);
        InvoicePublicLinkUseCase.Link link = links.link(invoice.id(), "https://keevo.ad2s.cm/", SCHEMA);
        assertThat(link.url()).startsWith("https://keevo.ad2s.cm/api/v1/invoices/shared?token=");
        assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(signer.verify(token(link)).version()).isZero();

        when(presentations.preferences()).thenReturn(new DocumentPreferences(null, null,
                DocumentPreferences.IMPORTED_LINK_DAYS));
        assertThat(links.link(invoice.id(), "https://keevo.ad2s.cm", SCHEMA).expiresAt())
                .isEqualTo(NOW.plus(Duration.ofDays(90)));
    }

    @Test
    @DisplayName("version: a link signed before a rotation no longer opens; 14.2 tokens read as version 0")
    void version() {
        UUID id = UUID.randomUUID();
        when(presentations.find(id)).thenReturn(Optional.of(version(2, null)));
        links.assertCurrentVersion(signer.verify(signer.sign(SCHEMA, id, NOW.plusSeconds(60), 2)));
        assertThatThrownBy(() -> links.assertCurrentVersion(signer.verify(signer.sign(SCHEMA, id, NOW.plusSeconds(60)))))
                .isInstanceOf(DomainException.class).hasFieldOrPropertyWithValue("domainCode", "INVOICE_LINK_INVALID");
        when(presentations.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> links.assertCurrentVersion(signer.verify(signer.sign(SCHEMA, id, NOW.plusSeconds(60),
                2)))).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("rotation: version bumped, the old Bref token retired from the registry, new link at the new version")
    void rotation() {
        Invoice invoice = issued();
        UUID legacy = UUID.randomUUID();
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(presentations.find(invoice.id())).thenReturn(Optional.of(version(0, legacy)),
                Optional.of(version(1, null)));
        when(presentations.rotateLink(eq(invoice.id()), any())).thenReturn(Optional.of(1));
        when(presentations.preferences()).thenReturn(DocumentPreferences.DEFAULTS);
        InvoicePublicLinkUseCase.Link link = links.rotate(invoice.id(), "https://k", SCHEMA);
        verify(registry).unregister(legacy);
        assertThat(signer.verify(token(link)).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("legacy Bref link: unknown → 404, expired → 410, tenant not active → 404, else its target")
    void legacy() {
        UUID token = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        when(registry.find(token)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> links.resolveLegacy(token))
                .hasFieldOrPropertyWithValue("domainCode", "INVOICE_LINK_INVALID");

        when(registry.find(token)).thenReturn(Optional.of(new LegacyInvoiceLinkRegistry.Target(SCHEMA, invoiceId,
                NOW.minusSeconds(1))));
        assertThatThrownBy(() -> links.resolveLegacy(token))
                .hasFieldOrPropertyWithValue("domainCode", "INVOICE_LINK_EXPIRED");

        when(registry.find(token)).thenReturn(Optional.of(new LegacyInvoiceLinkRegistry.Target(SCHEMA, invoiceId,
                null)));
        when(identity.isActive(SCHEMA)).thenReturn(false);
        assertThatThrownBy(() -> links.resolveLegacy(token))
                .hasFieldOrPropertyWithValue("domainCode", "INVOICE_LINK_INVALID");
        when(identity.isActive(SCHEMA)).thenReturn(true);
        assertThat(links.resolveLegacy(token).invoiceId()).isEqualTo(invoiceId);
    }

    @Test
    @DisplayName("import attach: reference checked (BRF-XXXXXXXX), stored on the invoice and in the registry")
    void attach() {
        Invoice invoice = issued();
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        UUID token = UUID.randomUUID();
        assertThatThrownBy(() -> links.attachLegacy(invoice.id(), token, "BRF-xyz", null, SCHEMA))
                .hasFieldOrPropertyWithValue("domainCode", "VALIDATION_ERROR");
        verify(registry, never()).register(any(), any(), any(), any());
        links.attachLegacy(invoice.id(), token, InvoicePresentation.legacyReferenceOf(
                UUID.fromString("1a2b3c4d-0000-4000-8000-000000000000")), null, SCHEMA);
        verify(presentations).attachLegacy(eq(invoice.id()), eq(token), eq("BRF-1A2B3C4D"), any());
        verify(registry).register(token, SCHEMA, invoice.id(), null);
    }
}
