package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Templates, ticket widths, presentation and document preferences (Story 20.7)")
class InvoicePresentationTest {

    @Test
    @DisplayName("8 templates, parsed from the enum name or Bref's code; 5 narrow ones; unknown → IAE")
    void templates() {
        assertThat(InvoiceTemplate.values()).hasSize(8);
        assertThat(InvoiceTemplate.parse("ticket-classic")).contains(InvoiceTemplate.TICKET_CLASSIC);
        assertThat(InvoiceTemplate.parse(" Modern ")).contains(InvoiceTemplate.MODERN);
        assertThat(InvoiceTemplate.parse(null)).isEmpty();
        assertThat(InvoiceTemplate.values()).filteredOn(InvoiceTemplate::narrow).hasSize(5);
        assertThatThrownBy(() -> InvoiceTemplate.parse("fancy")).isInstanceOf(IllegalArgumentException.class);
        assertThat(InvoiceTemplate.fromStored("RECEIPT")).isEqualTo(InvoiceTemplate.RECEIPT);
        assertThat(InvoiceTemplate.fromStored("??")).isNull();
    }

    @Test
    @DisplayName("ticket width 58 / 80 / A4 (« 58mm » accepted), default 80; unknown → IAE")
    void widths() {
        assertThat(TicketWidth.parse("58mm")).contains(TicketWidth.MM_58);
        assertThat(TicketWidth.parse("a4")).contains(TicketWidth.A4);
        assertThat(TicketWidth.parse("")).isEmpty();
        assertThatThrownBy(() -> TicketWidth.parse("110")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TicketWidth.DEFAULT).isEqualTo(TicketWidth.MM_80);
        assertThat(TicketWidth.A4.contentWidthMm()).isEqualTo(110);
    }

    @Test
    @DisplayName("effective layout: the document's, else the tenant's, else Keevo standard / 80 mm")
    void effective() {
        DocumentPreferences tenant = new DocumentPreferences(InvoiceTemplate.MINIMAL, TicketWidth.MM_58, null);
        assertThat(InvoicePresentation.NONE.effectiveTemplate(DocumentPreferences.DEFAULTS)).isNull();
        assertThat(InvoicePresentation.NONE.effectiveWidth(DocumentPreferences.DEFAULTS)).isEqualTo(TicketWidth.MM_80);
        assertThat(InvoicePresentation.NONE.effectiveTemplate(tenant)).isEqualTo(InvoiceTemplate.MINIMAL);
        InvoicePresentation own = new InvoicePresentation(InvoiceTemplate.CLASSIC, TicketWidth.A4, "c.png", null, 3,
                null, null);
        assertThat(own.effectiveTemplate(tenant)).isEqualTo(InvoiceTemplate.CLASSIC);
        assertThat(own.effectiveWidth(tenant)).isEqualTo(TicketWidth.A4);
        assertThat(own.signed(SignatureRole.CLIENT)).isTrue();
        assertThat(own.signed(SignatureRole.SELLER)).isFalse();
    }

    @Test
    @DisplayName("Bref reference BRF- + 8 hex (upper case); link lifetime 30 days by default, 1–365 accepted")
    void legacyAndLifetime() {
        assertThat(InvoicePresentation.legacyReferenceOf(UUID.fromString("abcdef12-3456-4789-8abc-def012345678")))
                .isEqualTo("BRF-ABCDEF12");
        assertThatThrownBy(() -> new InvoicePresentation(null, null, null, null, 0, null, "FAC-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DocumentPreferences.DEFAULTS.linkDays()).isEqualTo(30);
        assertThat(new DocumentPreferences(null, null, 90).linkDays()).isEqualTo(90);
        assertThatThrownBy(() -> new DocumentPreferences(null, null, 366)).isInstanceOf(DomainException.class);
        assertThat(SignatureRole.parse("Client")).contains(SignatureRole.CLIENT);
        assertThat(SignatureRole.parse("witness")).isEmpty();
    }
}
