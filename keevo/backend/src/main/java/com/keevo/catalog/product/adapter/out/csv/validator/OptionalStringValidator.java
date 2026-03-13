package com.keevo.catalog.product.adapter.out.csv.validator;

/**
 * OptionalStringValidator — validates an optional string field.
 * Returns null if blank; trims otherwise.
 */
public class OptionalStringValidator implements CsvColumnValidator<String> {

    @Override
    public String validate(String rawValue, int lineNumber, String columnName) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return rawValue.trim();
    }
}
