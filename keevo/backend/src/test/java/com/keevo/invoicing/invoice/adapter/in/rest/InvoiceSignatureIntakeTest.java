package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InvoiceSignatureIntake — Story 22.19 (D21)")
class InvoiceSignatureIntakeTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d};

    @Test
    @DisplayName("PNG data URL decoded; absent / blank = no signature")
    void decodes() {
        String url = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
        var s = InvoiceSignatureIntake.decode(url, null);
        assertThat(s.seller()).isEqualTo(PNG);
        assertThat(s.client()).isNull();
        assertThat(InvoiceSignatureIntake.decode(" ", null).none()).isTrue();
    }

    @Test
    @DisplayName("not a PNG, not base64, or over 200 KB → French VALIDATION_ERROR")
    void refuses() {
        String jpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(PNG);
        String big = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[210 * 1024]);
        for (String bad : new String[]{jpeg, "data:image/png;base64,@@@", big,
                Base64.getEncoder().encodeToString("GIF89a....".getBytes())}) {
            assertThatThrownBy(() -> InvoiceSignatureIntake.decode(null, bad))
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("userMessage",
                            "La signature doit être une image PNG de 200 Ko au plus");
        }
    }
}
