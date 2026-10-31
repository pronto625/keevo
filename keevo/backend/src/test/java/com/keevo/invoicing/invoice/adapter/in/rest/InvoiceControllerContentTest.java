package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceClientDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceLineRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceRequestDto;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InvoiceController — draft content mapping (Story 15.5b)")
class InvoiceControllerContentTest {

    private static InvoiceRequestDto request(String treatment) {
        return new InvoiceRequestDto(null, new InvoiceClientDto(null, "Awa", null, null, null),
                List.of(new InvoiceLineRequestDto("Riz", BigDecimal.ONE, 1000L, treatment)));
    }

    @Test
    @DisplayName("an unknown VAT treatment reaching the mapping → French 400, never a 500")
    void unknownTreatment() {
        assertThatThrownBy(() -> InvoiceController.content(request("BOGUS")))
                .isInstanceOf(InvalidRequestParameterException.class);
    }

    @Test
    @DisplayName("a valid line maps (STANDARD by default)")
    void valid() {
        assertThat(InvoiceController.content(request(null)).lines()).hasSize(1);
    }
}
