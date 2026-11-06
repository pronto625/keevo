package com.keevo.shared.domain;

import com.keevo.invoicing.invoice.application.service.InvoicePhones;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PhoneNumbers.toE164 — one normalization for invoicing and the crm (Story 20.2)")
class PhoneNumbersToE164Test {

    @Test
    void normalizesTypedForms() {
        assertThat(PhoneNumbers.toE164("699001122", PhoneNumbers.CAMEROON)).isEqualTo("+237699001122");
        assertThat(PhoneNumbers.toE164("+237 6 99 00 11 22", PhoneNumbers.CAMEROON)).isEqualTo("+237699001122");
        assertThat(PhoneNumbers.toE164("00237699001122", PhoneNumbers.CAMEROON)).isEqualTo("+237699001122");
        assertThat(PhoneNumbers.toE164("237699001122", PhoneNumbers.CAMEROON)).isEqualTo("+237699001122");
        assertThat(PhoneNumbers.toE164("(+33) 6.12.34.56.78", PhoneNumbers.CAMEROON)).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.toE164("+237699001122", PhoneNumbers.CAMEROON)).isEqualTo("+237699001122");
    }

    @Test
    void withoutDefaultCountry_nineDigitsGetOnlyAPlus() {
        assertThat(PhoneNumbers.toE164("699001122", null)).isEqualTo("+699001122");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "abc", "06-12", "+0123456789", "+12345", "+1234567890123456"})
    void invalidIsNull(String raw) {
        assertThat(PhoneNumbers.toE164(raw, PhoneNumbers.CAMEROON)).isNull();
    }

    @Test
    void nullIsNull() {
        assertThat(PhoneNumbers.toE164(null, PhoneNumbers.CAMEROON)).isNull();
    }

    /** InvoicePhones now delegates: same results, same invoicing error codes. */
    @ParameterizedTest
    @ValueSource(strings = {"677 00 01 11", "+237 677-00-01-11", "237677000111", "0033 6 12 34 56 78",
            "+237699001122", "(237) 699.00.11.22"})
    void invoicePhonesParity(String raw) {
        assertThat(InvoicePhones.normalize(raw)).isEqualTo(PhoneNumbers.toE164(raw, PhoneNumbers.CAMEROON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "06-12", "--", "+0123456789"})
    void invoicePhonesKeepsItsInvalidCode(String raw) {
        assertThatThrownBy(() -> InvoicePhones.normalize(raw))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("INVOICE_PHONE_INVALID"));
    }
}
