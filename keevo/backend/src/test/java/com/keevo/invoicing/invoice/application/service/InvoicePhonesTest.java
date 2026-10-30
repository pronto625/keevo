package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InvoicePhones (Story 14.2)")
class InvoicePhonesTest {

    @Test
    @DisplayName("normalize: local Cameroonian number gets +237; E.164 kept; 00 prefix; separators dropped")
    void normalize() {
        assertThat(InvoicePhones.normalize("677 00 01 11")).isEqualTo("+237677000111");
        assertThat(InvoicePhones.normalize("+237 677-00-01-11")).isEqualTo("+237677000111");
        assertThat(InvoicePhones.normalize("237677000111")).isEqualTo("+237677000111");
        assertThat(InvoicePhones.normalize("0033 6 12 34 56 78")).isEqualTo("+33612345678");
    }

    @Test
    @DisplayName("normalize: blank → INVOICE_PHONE_REQUIRED, malformed → INVOICE_PHONE_INVALID")
    void invalid() {
        assertThatThrownBy(() -> InvoicePhones.normalize("  "))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_PHONE_REQUIRED"));
        assertThatThrownBy(() -> InvoicePhones.normalize(null))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_PHONE_REQUIRED"));
        for (String bad : new String[]{"abc", "12", "+0123456789", "6770001110000000000"}) {
            assertThatThrownBy(() -> InvoicePhones.normalize(bad)).as(bad)
                    .satisfies(e -> assertThat(((DomainException) e).getDomainCode())
                            .isEqualTo("INVOICE_PHONE_INVALID"));
        }
    }

    @Test
    @DisplayName("mask: « +237 6•• ••• 111 », other countries keep the last 3 digits only")
    void mask() {
        assertThat(InvoicePhones.mask("+237677000111")).isEqualTo("+237 6•• ••• 111");
        assertThat(InvoicePhones.mask("+33612345678")).isEqualTo("+33 ••• 678");
        assertThat(InvoicePhones.mask("+12025550123")).isEqualTo("+12 ••• 123");
        assertThat(InvoicePhones.mask("+2250700112233")).isEqualTo("+22 ••• 233");
        assertThat(InvoicePhones.mask("+2250700112233")).doesNotContain("07001122");
        assertThat(InvoicePhones.mask(null)).isNull();
    }
}
