package com.keevo.catalog.product.adapter.out.csv.validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Story 17.12 — the French yes/no cell of the « suivi_stock » column. */
class OptionalYesNoValidatorTest {

    private final OptionalYesNoValidator validator = new OptionalYesNoValidator();

    private Boolean read(String raw) {
        return validator.validate(raw, 4, "Suivi du stock");
    }

    @Test
    void yesValues() {
        for (String v : new String[] {"oui", "o", "y", "1", "yes", "true", "vrai", " Oui ", "OUI"}) {
            assertThat(read(v)).as(v).isTrue();
        }
    }

    @Test
    void noValues() {
        for (String v : new String[] {"non", "n", "0", "no", "false", "faux", " NON ", "Non"}) {
            assertThat(read(v)).as(v).isFalse();
        }
    }

    @Test
    void absentOrBlank_isNull() {
        assertThat(read(null)).isNull();
        assertThat(read("")).isNull();
        assertThat(read("   ")).isNull();
    }

    @Test
    void otherValue_isAFrenchRowError() {
        CsvValidationException e = catchThrowableOfType(() -> read("peut-être"), CsvValidationException.class);

        assertThat(e.getLineNumber()).isEqualTo(4);
        assertThat(e.getColumnName()).isEqualTo("Suivi du stock");
        assertThat(e.getMessage()).isEqualTo("Suivi du stock : répondez « oui » ou « non »");
    }
}
