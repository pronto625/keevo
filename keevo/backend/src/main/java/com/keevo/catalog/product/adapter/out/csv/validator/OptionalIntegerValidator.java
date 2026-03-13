package com.keevo.catalog.product.adapter.out.csv.validator;

/**
 * OptionalIntegerValidator — validates an optional non-negative integer.
 * Returns 0 if the field is null or blank.
 */
public class OptionalIntegerValidator implements CsvColumnValidator<Integer> {

    @Override
    public Integer validate(String rawValue, int lineNumber, String columnName) {
        if (rawValue == null || rawValue.isBlank()) {
            return 0; // default
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value < 0) {
                throw new CsvValidationException(lineNumber, columnName,
                        columnName + " ne peut pas être négatif, reçu : " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new CsvValidationException(lineNumber, columnName,
                    columnName + " doit être un entier, reçu : '" + rawValue.trim() + "'");
        }
    }
}
