package com.keevo.catalog.product.adapter.out.csv.validator;

/**
 * PositiveIntegerValidator — validates a required strictly positive integer (> 0).
 */
public class PositiveIntegerValidator implements CsvColumnValidator<Integer> {

    @Override
    public Integer validate(String rawValue, int lineNumber, String columnName) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new CsvValidationException(lineNumber, columnName,
                    columnName + " est requis et doit être un entier positif");
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (value <= 0) {
                throw new CsvValidationException(lineNumber, columnName,
                        columnName + " doit être supérieur à 0, reçu : " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new CsvValidationException(lineNumber, columnName,
                    columnName + " doit être un entier, reçu : '" + rawValue.trim() + "'");
        }
    }
}
