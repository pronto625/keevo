package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("InvoiceRecipients — SMS number / email checks and masks (Story 20.8)")
class InvoiceRecipientsTest {

    private static ErrorCode code(Runnable r) {
        return catchThrowableOfType(DomainException.class, r::run).getErrorCode();
    }

    @Test
    @DisplayName("override first, else the client's value")
    void pick() {
        assertThat(InvoiceRecipients.pick("690000002", "677000111")).isEqualTo("690000002");
        assertThat(InvoiceRecipients.pick("  ", "677000111")).isEqualTo("677000111");
        assertThat(InvoiceRecipients.pick(null, null)).isNull();
    }

    @Test
    @DisplayName("SMS number: E.164, +237 for 9 digits; blank / malformed → SMS codes")
    void smsPhone() {
        assertThat(InvoiceRecipients.smsPhone("677 00 01 11")).isEqualTo("+237677000111");
        assertThat(InvoiceRecipients.smsPhone("+33612345678")).isEqualTo("+33612345678");
        assertThat(code(() -> InvoiceRecipients.smsPhone(null))).isEqualTo(ErrorCode.INVOICE_SMS_PHONE_REQUIRED);
        assertThat(code(() -> InvoiceRecipients.smsPhone("6990"))).isEqualTo(ErrorCode.INVOICE_SMS_PHONE_INVALID);
    }

    @Test
    @DisplayName("email: trimmed, domain lower-cased; blank / malformed / too long → email codes")
    void email() {
        assertThat(InvoiceRecipients.email("  Compta@Ndolo.CM ")).isEqualTo("Compta@ndolo.cm");
        assertThat(code(() -> InvoiceRecipients.email(" "))).isEqualTo(ErrorCode.INVOICE_EMAIL_REQUIRED);
        assertThat(InvoiceRecipients.email("o'brien+factures@mail.ndolo-sarl.cm"))
                .isEqualTo("o'brien+factures@mail.ndolo-sarl.cm");
        assertThat(InvoiceRecipients.email("tres.longue.adresse.comptabilite@ndolo.cm")).hasSizeGreaterThan(20);
        for (String bad : new String[]{"compta", "compta@ndolo", "a b@ndolo.cm", "a@@ndolo.cm", "a@ndolo.c",
                "a@" + "x".repeat(260) + ".cm",
                // Story 20.8 review: empty labels, leading hyphen, forbidden characters, dots
                "a@ndolo..cm", "a@.ndolo.cm", "a@-ndolo.cm", "a@ndolo-.cm", "<a>@ndolo.cm", "a\"b@ndolo.cm",
                "a,b@ndolo.cm", "a;b@ndolo.cm", "a\u0007@ndolo.cm", ".a@ndolo.cm", "a.@ndolo.cm", "a..b@ndolo.cm",
                "a@ndolo.c0m"}) {
            assertThat(code(() -> InvoiceRecipients.email(bad))).as(bad).isEqualTo(ErrorCode.INVOICE_EMAIL_INVALID);
        }
    }

    @Test
    @DisplayName("mask: email keeps first character and domain, phone as 14.2, null stays null")
    void mask() {
        assertThat(InvoiceRecipients.mask("compta@ndolo.cm")).isEqualTo("c***@ndolo.cm");
        assertThat(InvoiceRecipients.mask("+237677000111")).isEqualTo("+237 6•• ••• 111");
        assertThat(InvoiceRecipients.mask(null)).isNull();
    }
}
