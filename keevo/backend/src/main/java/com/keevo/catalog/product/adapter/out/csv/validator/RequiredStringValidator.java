package com.keevo.catalog.product.adapter.out.csv.validator;

/**
 * RequiredStringValidator — validates a required non-blank string (max 255 chars).
 */
public class RequiredStringValidator implements CsvColumnValidator<String> {

    private static final int MAX_LENGTH = 255;

    @Override
    public String validate(String rawValue, int lineNumber, String columnName) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new CsvValidationException(lineNumber, columnName,
                    columnName + " est requis et ne peut pas être vide");
        }
        String trimmed = rawValue.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new CsvValidationException(lineNumber, columnName,
                    columnName + " dépasse " + MAX_LENGTH + " caractères");
        }
        return trimmed;
    }
}
