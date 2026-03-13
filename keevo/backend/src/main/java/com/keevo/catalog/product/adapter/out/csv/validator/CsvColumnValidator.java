package com.keevo.catalog.product.adapter.out.csv.validator;

/**
 * CsvColumnValidator — Strategy interface for CSV cell validation.
 *
 * <p>GoF Pattern: <b>Strategy</b> — each field type has its own implementation.
 * Adding a new field type (e.g., boolean) only requires adding a new strategy class.
 *
 * @param <T> the parsed/validated type
 */
public interface CsvColumnValidator<T> {

    /**
     * Validate and parse the raw cell value.
     *
     * @param rawValue   the raw string from the CSV cell (may be null or blank)
     * @param lineNumber 1-based CSV data line number (for error reporting)
     * @param columnName name of the CSV column (for error reporting)
     * @return the validated/parsed value, or {@code null} if the field is optional and absent
     * @throws CsvValidationException if the value fails validation
     */
    T validate(String rawValue, int lineNumber, String columnName);
}
