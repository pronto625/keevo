package com.keevo.invoicing.invoice.adapter.out.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("21.7 — portal documents: payment state shown to the client")
class JdbcPortalDocumentsTest {

    @Test
    @DisplayName("cancelled wins; stored state kept; a PAID invoice without state is paid, else unpaid; French labels")
    void stateAndLabel() {
        assertThat(JdbcPortalDocuments.state("CANCELLED", "PARTIAL")).isEqualTo("CANCELLED");
        assertThat(JdbcPortalDocuments.state("ISSUED", "OVERDUE")).isEqualTo("OVERDUE");
        assertThat(JdbcPortalDocuments.state("PAID", null)).isEqualTo("PAID");
        assertThat(JdbcPortalDocuments.state("ISSUED", null)).isEqualTo("UNPAID");
        assertThat(JdbcPortalDocuments.label("CANCELLED")).isEqualTo("Annulée");
        assertThat(JdbcPortalDocuments.label("UNPAID")).isEqualTo("Non payée");
        assertThat(JdbcPortalDocuments.label("PARTIAL")).isEqualTo("Partiellement payée");
        assertThat(JdbcPortalDocuments.label("PAID")).isEqualTo("Payée");
        assertThat(JdbcPortalDocuments.label("OVERDUE")).isEqualTo("En retard");
    }

    @Test
    @DisplayName("no schema → no statement; an invalid schema name is refused before any SQL")
    void schemas() {
        JdbcPortalDocuments docs = new JdbcPortalDocuments(null, null, java.time.Clock.systemUTC());
        assertThat(docs.find(Map.of(), "https://keevo.ad2s.cm")).isEmpty();
        assertThatThrownBy(() -> docs.find(Map.of("kv_x\"; DROP TABLE x; --",
                java.util.List.of(java.util.UUID.randomUUID())), "https://keevo.ad2s.cm"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema name");
    }
}
