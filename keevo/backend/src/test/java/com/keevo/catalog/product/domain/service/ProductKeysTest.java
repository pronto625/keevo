package com.keevo.catalog.product.domain.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 20.1 — the Bref examples of {@code api-bref/src/product/product-key.spec.ts}, byte for byte. */
@DisplayName("ProductKeys — clé produit Bref (20.1)")
class ProductKeysTest {

    @Test
    @DisplayName("normalizeName ignore la casse, les accents et les espaces superflus")
    void normalizeName_bref_examples() {
        assertThat(ProductKeys.normalizeName("  Thé   VERT  ")).isEqualTo("the vert");
        assertThat(ProductKeys.normalizeName("CRÈME Brûlée")).isEqualTo("creme brulee");
        assertThat(ProductKeys.normalizeName("   ")).isEmpty();
    }

    @Test
    @DisplayName("normalizeName garde la ponctuation et replie les espaces Unicode comme JavaScript")
    void normalizeName_keeps_punctuation_and_folds_unicode_spaces() {
        assertThat(ProductKeys.normalizeName("Coca-Cola 1,5L !")).isEqualTo("coca-cola 1,5l !");
        assertThat(ProductKeys.normalizeName("Riz  parfumé\t5kg")).isEqualTo("riz parfume 5kg");
        assertThat(ProductKeys.normalizeName(null)).isEmpty();
    }

    @Test
    @DisplayName("isBarcodeLike accepte 8 à 14 chiffres")
    void isBarcodeLike_accepts_8_to_14_digits() {
        assertThat(ProductKeys.isBarcodeLike("12345678")).isTrue();
        assertThat(ProductKeys.isBarcodeLike("6001234567890")).isTrue();
        assertThat(ProductKeys.isBarcodeLike(" 12345678901234 ")).isTrue();
    }

    @Test
    @DisplayName("isBarcodeLike refuse les références libres, trop courtes ou trop longues")
    void isBarcodeLike_refuses_free_references() {
        assertThat(ProductKeys.isBarcodeLike("REF-001")).isFalse();
        assertThat(ProductKeys.isBarcodeLike("1234567")).isFalse();
        assertThat(ProductKeys.isBarcodeLike("123456789012345")).isFalse();
        assertThat(ProductKeys.isBarcodeLike("")).isFalse();
        assertThat(ProductKeys.isBarcodeLike(null)).isFalse();
        // non-ASCII digits are not barcodes (JavaScript \d is ASCII only)
        assertThat(ProductKeys.isBarcodeLike("١٢٣٤٥٦٧٨")).isFalse();
    }

    @Test
    @DisplayName("key utilise le code-barres quand la référence en est un")
    void key_uses_the_barcode() {
        assertThat(ProductKeys.key("Savon", "6001234567890")).isEqualTo("bc:6001234567890");
        assertThat(ProductKeys.key("Autre nom", "6001234567890")).isEqualTo("bc:6001234567890");
        assertThat(ProductKeys.key("Savon", " 6111234567890 ")).isEqualTo("bc:6111234567890");
    }

    @Test
    @DisplayName("key utilise le nom normalisé sinon, quelle que soit la référence")
    void key_uses_the_normalized_name_otherwise() {
        assertThat(ProductKeys.key("Chemise Bleue", "REF-A")).isEqualTo("nm:chemise bleue");
        assertThat(ProductKeys.key("  chemise   BLEUE ", "REF-B")).isEqualTo("nm:chemise bleue");
        assertThat(ProductKeys.key("Chemise Bleue", null)).isEqualTo("nm:chemise bleue");
        assertThat(ProductKeys.key("CRÈME Brûlée", null)).isEqualTo("nm:creme brulee");
        assertThat(ProductKeys.key("Savon", "REF-001")).isEqualTo("nm:savon");
    }

    @Test
    @DisplayName("key renvoie null sans code-barres ni nom exploitable")
    void key_is_null_without_barcode_or_name() {
        assertThat(ProductKeys.key("   ", "REF-A")).isNull();
        assertThat(ProductKeys.key("", null)).isNull();
    }

    @Test
    @DisplayName("cleanBarcode : vide → aucun, espaces retirés, plus de 100 caractères → 422")
    void cleanBarcode() {
        assertThat(ProductKeys.cleanBarcode(null)).isNull();
        assertThat(ProductKeys.cleanBarcode("   ")).isNull();
        assertThat(ProductKeys.cleanBarcode(" REF-001 ")).isEqualTo("REF-001");
        assertThat(ProductKeys.cleanBarcode("x".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> ProductKeys.cleanBarcode("x".repeat(101)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getUserMessage())
                            .isEqualTo("La référence ou le code-barres ne doit pas dépasser 100 caractères");
                });
    }
}
