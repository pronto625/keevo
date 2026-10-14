package com.keevo.catalog.product.adapter.out.csv;

import com.keevo.catalog.product.adapter.out.csv.validator.CsvValidationException;
import com.keevo.catalog.product.application.dto.CsvColumnMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * J12 — rejected CSV lines name the field in French (« Prix de vente », « Quantité initiale »…),
 * both as the error column and inside the message, never the raw key (« prix_vente »).
 */
class ProductCsvRowFactoryTest {

    private final ProductCsvRowFactory factory = new ProductCsvRowFactory();
    private final CsvColumnMapping mapping = new CsvColumnMapping(
            "nom", "prix_vente", "prix_achat", "cout_transport", "categorie", "sku",
            "quantite_initiale", "seuil_min");

    private CsvValidationException rejected(Map<String, String> row) {
        return catchThrowableOfType(
                () -> factory.fromRow(row, mapping, 3, UUID.randomUUID(), Map.of()),
                CsvValidationException.class);
    }

    @Test
    @DisplayName("missing sale price → « Prix de vente »")
    void missingPrice() {
        CsvValidationException e = rejected(Map.of("nom", "Riz", "prix_vente", ""));

        assertThat(e.getColumnName()).isEqualTo("Prix de vente");
        assertThat(e.getMessage()).isEqualTo("Prix de vente est requis et doit être un entier positif");
    }

    @Test
    @DisplayName("non-numeric initial quantity → « Quantité initiale »")
    void badQuantity() {
        CsvValidationException e = rejected(
                Map.of("nom", "Riz", "prix_vente", "1000", "quantite_initiale", "abc"));

        assertThat(e.getColumnName()).isEqualTo("Quantité initiale");
        assertThat(e.getMessage()).startsWith("Quantité initiale doit être un entier");
        assertThat(e.getMessage()).doesNotContain("quantite_initiale");
    }

    @Test
    @DisplayName("negative buy price / threshold → « Prix d'achat » / « Seuil minimum »")
    void negativeOptionalFields() {
        assertThat(rejected(Map.of("nom", "Riz", "prix_vente", "1000", "prix_achat", "-5"))
                .getColumnName()).isEqualTo("Prix d'achat");
        assertThat(rejected(Map.of("nom", "Riz", "prix_vente", "1000", "seuil_min", "-1"))
                .getColumnName()).isEqualTo("Seuil minimum");
        assertThat(rejected(Map.of("nom", "Riz", "prix_vente", "1000", "cout_transport", "x"))
                .getColumnName()).isEqualTo("Coût de transport");
    }

    @Test
    @DisplayName("missing name → « Nom »")
    void missingName() {
        CsvValidationException e = rejected(Map.of("nom", " ", "prix_vente", "1000"));

        assertThat(e.getColumnName()).isEqualTo("Nom");
        assertThat(e.getMessage()).startsWith("Nom est requis");
    }
}
