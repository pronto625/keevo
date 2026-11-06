package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InvoiceDocumentUseCase — PDF file names (Story 14.2, quotes Story 20.3)")
class InvoiceDocumentFilenameTest {

    private static final InvoicingSettings SELLER = new InvoicingSettings("Awa", null, null, null, null, false,
            new BigDecimal("19.25"), true, null, null);
    private static final ClientInfo CLIENT = new ClientInfo(null, "Société Ndolo", null, null, null);
    private static final List<LineInput> LINES = List.of(new LineInput("Riz", BigDecimal.ONE, 1000,
            VatTreatment.STANDARD));
    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    @Test
    @DisplayName("quote → devis-DV-XXXXXXXX.pdf (whatever its status); draft → brouillon.pdf; issued → FAC-….pdf")
    void filenames() {
        UUID id = UUID.fromString("ab12cd34-5678-4abc-8def-0123456789ab");
        Invoice quote = Invoice.quote(id, CLIENT, LINES, SELLER, null, T0);
        assertThat(InvoiceDocumentUseCase.filename(quote)).isEqualTo("devis-DV-AB12CD34.pdf");
        assertThat(InvoiceDocumentUseCase.filename(quote.converted(UUID.randomUUID(), T0)))
                .isEqualTo("devis-DV-AB12CD34.pdf");
        Invoice draft = Invoice.draft(id, CLIENT, LINES, SELLER, null, T0);
        assertThat(InvoiceDocumentUseCase.filename(draft)).isEqualTo("brouillon.pdf");
        assertThat(InvoiceDocumentUseCase.filename(draft.issue("FAC-2026-000003", LocalDate.of(2026, 11, 6), SELLER,
                T0))).isEqualTo("FAC-2026-000003.pdf");
    }
}
