package com.keevo.invoicing.invoice.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.domain.VatRates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.5 — every case of {@code keevo/contracts/invoice-calculation-cases.json} (shared with the Bref React Native
 * app) through the served path: {@link Invoice#draft} with the seller's settings and the document's terms, then
 * {@link VatCalculator#compute}. The seller's rate is its country's ({@link VatRates}); a case whose document rate
 * differs carries it as the document's own rate.
 */
@DisplayName("Story 20.5 — published calculation cases (Bref-identical totals)")
class InvoiceCalculationCasesTest {

    private static final ClientInfo CLIENT = new ClientInfo(null, "Client", null, null, null);

    @Test
    @DisplayName("≥ 50 cases, every source present, every expected total matches exactly")
    void everyCase() throws Exception {
        JsonNode contract = new ObjectMapper().readTree(
                Path.of("..", "contracts", "invoice-calculation-cases.json").toFile());
        JsonNode cases = contract.path("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(50);
        Set<String> sources = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            sources.add(c.path("source").asText());
            InvoiceTotals t = run(c);
            JsonNode e = c.path("expected");
            InvoiceTotals expected = new InvoiceTotals(e.path("totalHt").asLong(), e.path("totalVat").asLong(),
                    e.path("totalTtc").asLong(), e.path("subtotal").asLong(), c.path("discount").asLong(),
                    c.path("delivery").asLong());
            if (!expected.equals(t)) {
                failures.add(c.path("id").asText() + ": expected " + expected + " but was " + t);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(sources).contains("bref-dump", "bref-formula", "keevo-ttc", "keevo-ht");
        assertThat(cases.findValues("source").stream().filter(s -> s.asText().equals("bref-dump")).count())
                .isEqualTo(19);
    }

    private static InvoiceTotals run(JsonNode c) {
        boolean ttc = "TTC".equals(c.path("mode").asText());
        boolean registered = c.path("vatRegistered").asBoolean();
        BigDecimal documentRate = new BigDecimal(c.path("vatRate").asText());
        // a country outside Bref's table has no rate: the seller keeps 19.25, the case's own rate is the document's
        BigDecimal sellerRate = VatRates.forCountry(c.path("country").asText())
                .orElse(InvoicingSettings.DEFAULT_VAT_RATE);
        InvoicingSettings settings = new InvoicingSettings("Vendeur", null, null, null, null, registered, sellerRate,
                ttc, null, null);
        BigDecimal own = documentRate.compareTo(sellerRate) == 0 ? null : documentRate;
        InvoiceTerms terms = new InvoiceTerms(c.path("discount").asLong(), c.path("delivery").asLong(), own,
                c.path("vatExempt").asBoolean(), null);
        List<LineInput> lines = new ArrayList<>();
        for (JsonNode l : c.path("lines")) {
            String treatment = l.path("treatment").asText("STANDARD");
            lines.add(new LineInput("Article", new BigDecimal(l.path("quantity").asText()),
                    l.path("unitPrice").asLong(), VatTreatment.valueOf(treatment)));
        }
        return Invoice.draft(UUID.randomUUID(), CLIENT, lines, terms, settings, null, Instant.EPOCH).totals();
    }
}
