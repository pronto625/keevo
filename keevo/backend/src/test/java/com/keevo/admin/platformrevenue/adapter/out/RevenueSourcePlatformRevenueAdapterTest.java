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
        // 20-14 / 20-11: documents with their store, and the taxed relation (bridge invoice + invoicing_settings joins)
        String withStore = RevenueSourcePlatformRevenueAdapter.qualify(RevenueRelations.documents(true, true, true),
                "kv_abc123");
        assertThat(withStore).contains("FROM kv_abc123.invoices i").contains("JOIN kv_abc123.invoices i");
        String taxed = RevenueSourcePlatformRevenueAdapter.qualify(RevenueRelations.taxedDocuments(true, true, true),
                "kv_abc123");
        assertThat(taxed).contains("LEFT JOIN kv_abc123.invoices bi").contains("LEFT JOIN kv_abc123.invoicing_settings st");
        Matcher t = REFERENCE.matcher(taxed);
        while (t.find()) {
            assertThat(t.group(1)).startsWith("kv_abc123.");
        }
        assertThat(RevenueSourcePlatformRevenueAdapter.qualify(RevenueRelations.taxedSales(), "kv_abc123"))
                .contains("FROM kv_abc123.sales s");
        assertThat(RevenueSourcePlatformRevenueAdapter.qualify(RevenueRelations.lines(true, true), "kv_abc123"))
                .contains("FROM kv_abc123.sale_items si JOIN kv_abc123.sales s")
                .contains("LEFT JOIN kv_abc123.invoice_items it");
    }

    @Test
    @DisplayName("keywords in any case are qualified; a documents-only relation (no sales) too")
    void caseInsensitiveAndDocumentsOnly() {
        assertThat(RevenueSourcePlatformRevenueAdapter.qualify("(select 1 from sales s join invoices i on true)",
                "kv_abc123")).isEqualTo("(select 1 from kv_abc123.sales s join kv_abc123.invoices i on true)");
        assertThat(RevenueSourcePlatformRevenueAdapter.qualify(RevenueRelations.documents(false, true), "kv_abc123"))
                .contains("FROM kv_abc123.invoices i").doesNotContain("FROM sales");
    }

    @Test
    @DisplayName("LATERAL and comma joins are refused with a clear message")
    void lateralAndCommaJoinRefused() {
        assertThatThrownBy(() -> RevenueSourcePlatformRevenueAdapter.qualify(
                "(SELECT 1 FROM sales s LEFT JOIN LATERAL (SELECT 1) x ON TRUE)", "kv_abc123"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LATERAL");
        assertThatThrownBy(() -> RevenueSourcePlatformRevenueAdapter.qualify(
                "(SELECT 1 FROM sales s, invoices i WHERE TRUE)", "kv_abc123"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("comma join");
        assertThatThrownBy(() -> RevenueSourcePlatformRevenueAdapter.qualify(
                "(select 1 from sales, invoices)", "kv_abc123"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("comma join");
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
