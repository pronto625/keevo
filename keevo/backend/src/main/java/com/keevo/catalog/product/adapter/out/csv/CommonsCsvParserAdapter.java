package com.keevo.catalog.product.adapter.out.csv;

import com.keevo.catalog.product.domain.port.out.CsvParserPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CommonsCsvParserAdapter — CSV parsing adapter using Apache Commons CSV.
 *
 * <p>Handles UTF-8 BOM, Windows CRLF, and empty files.
 * Delegates to {@link CSVFormat} for low-level parsing.
 */
@Component
public class CommonsCsvParserAdapter implements CsvParserPort {

    // UTF-8 BOM bytes: 0xEF 0xBB 0xBF
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Override
    public List<Map<String, String>> parse(InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            if (bytes.length == 0) {
                throw new DomainException(ErrorCode.CSV_PARSE_ERROR, "Le fichier CSV est vide");
            }
            // Strip BOM if present
            if (bytes.length >= 3
                    && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]) {
                bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
            }

            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .setIgnoreEmptyLines(true)
                    .build();

            List<Map<String, String>> rows = new ArrayList<>();
            try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
                 CSVParser parser = new CSVParser(reader, format)) {

                for (CSVRecord record : parser) {
                    Map<String, String> row = new LinkedHashMap<>();
                    parser.getHeaderNames().forEach(h -> row.put(h, record.get(h)));
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                throw new DomainException(ErrorCode.CSV_PARSE_ERROR,
                        "Le fichier CSV ne contient aucune ligne de données");
            }
            return rows;

        } catch (DomainException e) {
            throw e;
        } catch (IOException e) {
            throw new DomainException(ErrorCode.CSV_PARSE_ERROR,
                    "Impossible de lire le fichier CSV : " + e.getMessage());
        }
    }
}
