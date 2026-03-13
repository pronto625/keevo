package com.keevo.catalog.product.adapter.out.csv;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for CommonsCsvParserAdapter — Story 2.4, Task 1.3.
 *
 * <p>Covers: standard parsing, empty file rejection, UTF-8 BOM stripping,
 * Windows CRLF line endings.
 */
class CommonsCsvParserAdapterTest {

    private final CommonsCsvParserAdapter adapter = new CommonsCsvParserAdapter();

    @Test
    @DisplayName("Parses valid CSV with header row → returns list of row maps")
    void shouldParseValidCsvWithHeaderRow() {
        String csv = "nom,prix_vente,quantite\nProduit A,1000,5\nProduit B,2000,10\n";
        InputStream input = new ByteArrayInputStream(csv.getBytes());

        List<Map<String, String>> rows = adapter.parse(input);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("nom", "Produit A")
                                .containsEntry("prix_vente", "1000");
        assertThat(rows.get(1)).containsEntry("nom", "Produit B")
                                .containsEntry("quantite", "10");
    }

    @Test
    @DisplayName("Empty file → throws DomainException CSV_PARSE_ERROR")
    void shouldThrowWhenFileIsEmpty() {
        InputStream empty = new ByteArrayInputStream(new byte[0]);

        assertThatThrownBy(() -> adapter.parse(empty))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("vide");
    }

    @Test
    @DisplayName("CSV with UTF-8 BOM prefix → BOM stripped, rows parsed correctly")
    void shouldHandleBomUtf8Prefix() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String csvBody = "nom,prix_vente\nProduit BOM,999\n";
        byte[] body = csvBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);

        List<Map<String, String>> rows = adapter.parse(new ByteArrayInputStream(all));

        assertThat(rows).hasSize(1);
        // Header "nom" must not start with BOM character
        assertThat(rows.get(0)).containsKey("nom");
        assertThat(rows.get(0).get("nom")).isEqualTo("Produit BOM");
    }

    @Test
    @DisplayName("CSV with Windows CRLF line endings → rows parsed correctly")
    void shouldHandleWindowsCrLfLineEndings() {
        String csv = "nom,prix_vente\r\nProduit CRLF,1500\r\nProduit B,2500\r\n";
        InputStream input = new ByteArrayInputStream(
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<Map<String, String>> rows = adapter.parse(input);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("nom")).isEqualTo("Produit CRLF");
        assertThat(rows.get(1).get("prix_vente")).isEqualTo("2500");
    }

    @Test
    @DisplayName("CSV with only header row (no data) → throws DomainException CSV_PARSE_ERROR")
    void shouldThrowWhenCsvHasOnlyHeaderAndNoDataRows() {
        String csv = "nom,prix_vente\n";
        InputStream input = new ByteArrayInputStream(csv.getBytes());

        assertThatThrownBy(() -> adapter.parse(input))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("aucune ligne de données");
    }
}
