package com.keevo.admin.platformrevenue.adapter.out;

import com.keevo.reporting.revenue.adapter.out.persistence.RevenueRelations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("20.13 — platform revenue adapter: the 20.9 relations qualified with the tenant schema")
class RevenueSourcePlatformRevenueAdapterTest {

    private static final Pattern REFERENCE = Pattern.compile("\\b(?:FROM|JOIN)\\s+([A-Za-z_][\\w.]*)");

    @Test
    @DisplayName("every table of the documents relation (sales only and with invoicing) is schema-qualified")
    void everyTableQualified() {
        for (boolean invoicing : List.of(false, true)) {
            String qualified = RevenueSourcePlatformRevenueAdapter.qualify(
                    RevenueRelations.documents(true, invoicing), "kv_abc123");
            Matcher m = REFERENCE.matcher(qualified);
            int references = 0;
            while (m.find()) {
                references++;
                assertThat(m.group(1)).startsWith("kv_abc123.");
            }
            assertThat(references).isEqualTo(invoicing ? 4 : 1); // sales | + invoices, credit_notes JOIN invoices
        }
        assertThat(RevenueSourcePlatformRevenueAdapter.qualify(RevenueRelations.lines(true, true), "kv_abc123"))
                .contains("FROM kv_abc123.sale_items si JOIN kv_abc123.sales s")
                .contains("LEFT JOIN kv_abc123.invoice_items it");
    }

    @Test
    @DisplayName("a table the adapter does not know, or an invalid schema, is refused")
    void unknownTableOrSchemaRefused() {
        assertThatThrownBy(() -> RevenueSourcePlatformRevenueAdapter.qualify(
                "(SELECT 1 FROM sales s JOIN refunds r ON TRUE)", "kv_abc123"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refunds");
        assertThatThrownBy(() -> RevenueSourcePlatformRevenueAdapter.qualify(
                RevenueRelations.POS_DOCUMENTS, "public; DROP"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
