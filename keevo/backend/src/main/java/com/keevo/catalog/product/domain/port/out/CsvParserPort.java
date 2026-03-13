package com.keevo.catalog.product.domain.port.out;

import com.keevo.shared.domain.exception.DomainException;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * CsvParserPort — Output port for parsing CSV files.
 *
 * <p>Returns rows as ordered header→value maps, preserving insertion order.
 * Implementation: {@link com.keevo.catalog.product.adapter.out.csv.CommonsCsvParserAdapter}.
 */
public interface CsvParserPort {

    /**
     * Parse a CSV input stream.
     *
     * @param inputStream CSV bytes (UTF-8 or UTF-8-BOM)
     * @return ordered list of rows as header→value maps (key = original header, case-preserved)
     * @throws DomainException CSV_PARSE_ERROR if file is malformed or contains 0 data rows
     */
    List<Map<String, String>> parse(InputStream inputStream);
}
