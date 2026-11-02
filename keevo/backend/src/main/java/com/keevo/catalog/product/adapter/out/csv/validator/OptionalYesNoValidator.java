package com.keevo.catalog.product.adapter.out.csv.validator;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * OptionalYesNoValidator — an optional French yes/no cell (Story 17.12, column {@code suivi_stock}).
 *
 * <p>Blank or absent ({@code null}) → {@code null} (the caller applies its default). Accepted after trimming,
 * case- and accent-insensitive: « oui / o / vrai / 1 / yes / y / true » and « non / n / faux / 0 / no / false ».
 * Anything else is a row error with a French message.
 */
public class OptionalYesNoValidator implements CsvColumnValidator<Boolean> {

    private static final Set<String> YES = Set.of("oui", "o", "vrai", "1", "yes", "y", "true");
    private static final Set<String> NO = Set.of("non", "n", "faux", "0", "no", "false");

    @Override
    public Boolean validate(String rawValue, int lineNumber, String columnName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String v = Normalizer.normalize(rawValue.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        if (YES.contains(v)) return Boolean.TRUE;
        if (NO.contains(v)) return Boolean.FALSE;
        throw new CsvValidationException(lineNumber, columnName,
                columnName + " : répondez « oui » ou « non »");
    }
}
