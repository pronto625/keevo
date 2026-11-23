package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InvoiceTerms.issueDateOr — Story 22.19 (D4)")
class InvoiceTermsIssueDate2219Test {

    private static InvoiceTerms requested(LocalDate day) {
        return new InvoiceTerms(0, 0, null, false, null, null, day);
    }

    @Test
    @DisplayName("earlier day of the year kept; previous year → 1 January; future refused")
    void rules() {
        LocalDate today = LocalDate.of(2027, 1, 2);
        assertThat(requested(LocalDate.of(2027, 1, 1)).issueDateOr(today)).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(requested(LocalDate.of(2026, 12, 31)).issueDateOr(today)).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(requested(null).issueDateOr(today)).isEqualTo(today);
        assertThatThrownBy(() -> requested(LocalDate.of(2027, 1, 3)).issueDateOr(today))
                .isInstanceOf(DomainException.class);
    }
}
