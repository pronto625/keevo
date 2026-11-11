package com.keevo.migration.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 24.4 — the Java port of Bref's {@code buildProductKey} gives Bref's keys. */
@DisplayName("24.4 — Bref product keys")
class BrefProductKeysTest {

    @Test
    @DisplayName("name key: accents removed, lower-cased, JavaScript white space collapsed and trimmed")
    void nameKey() {
        assertThat(BrefProductKeys.key("  Riz  Parfumé 5 KG ", "REF-AB12")).isEqualTo("nm:riz parfume 5 kg");
        assertThat(BrefProductKeys.key("Crème\tbrûlée　", null)).isEqualTo("nm:creme brulee");
        assertThat(BrefProductKeys.key("Ça", "")).isEqualTo("nm:ca");
    }

    @Test
    @DisplayName("barcode key: an 8–14 digit reference (trimmed) wins over the name")
    void barcodeKey() {
        assertThat(BrefProductKeys.key("Coca", " 6111234567890 ")).isEqualTo("bc:6111234567890");
        assertThat(BrefProductKeys.key("Coca", "1234567")).isEqualTo("nm:coca");
        assertThat(BrefProductKeys.key("Coca", "123456789012345")).isEqualTo("nm:coca");
        assertThat(BrefProductKeys.barcodeLike("12345678")).isTrue();
        assertThat(BrefProductKeys.barcodeLike("1234567a")).isFalse();
    }

    @Test
    @DisplayName("no key for a blank name without barcode")
    void noKey() {
        assertThat(BrefProductKeys.key("   ", null)).isNull();
        assertThat(BrefProductKeys.key(null, "REF")).isNull();
    }
}
