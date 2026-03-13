package com.keevo.catalog.product.adapter.out.csv.validator;

/**
 * Exception thrown by {@link CsvColumnValidator} when a cell value fails validation.
 * Carries line number and column name for error reporting.
 */
public class CsvValidationException extends RuntimeException {

    private final int    lineNumber;
    private final String columnName;

    public CsvValidationException(int lineNumber, String columnName, String message) {
        super(message);
        this.lineNumber = lineNumber;
        this.columnName = columnName;
    }

    public int    getLineNumber() { return lineNumber; }
    public String getColumnName() { return columnName; }
}
