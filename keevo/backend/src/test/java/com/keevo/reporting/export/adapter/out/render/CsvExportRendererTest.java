package com.keevo.reporting.export.adapter.out.render;

import com.keevo.reporting.export.domain.port.out.ExportRenderer.RenderSession;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CsvExportRenderer (v1s-18-1)")
class CsvExportRendererTest {

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("qty", "Quantité", ExportColumnType.INTEGER),
            new ExportColumn("amount", "Montant", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("rate", "Taux", ExportColumnType.DECIMAL),
            new ExportColumn("active", "Actif", ExportColumnType.BOOLEAN),
            new ExportColumn("day", "Jour", ExportColumnType.DATE),
            new ExportColumn("at", "Le", ExportColumnType.DATETIME));

    private final CsvExportRenderer renderer = new CsvExportRenderer();

    @Test
    @DisplayName("single dataset: UTF-8 BOM, ';' separator, CRLF, header of labels, typed cells in WAT")
    void singleDataset() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = renderer.open(out, 1);
        s.beginDataset("stores", "Boutiques", COLUMNS);
        // 23:30 UTC on 31 Dec = 00:30 WAT on 1 Jan
        s.row(List.of("Pagne wax", 3L, new BigDecimal("15000.4"), new BigDecimal("12.5"), true,
                Instant.parse("2025-12-31T23:30:00Z"), Instant.parse("2025-12-31T23:30:00Z")));
        s.row(Arrays.asList("Sac; \"cuir\"", null, 2500, null, false, LocalDate.of(2026, 2, 3), null));
        s.endDataset();
        s.close();

        byte[] bytes = out.toByteArray();
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8).substring(1);
        assertThat(csv).isEqualTo(
                "Nom;Quantité;Montant;Taux;Actif;Jour;Le\r\n"
                        + "Pagne wax;3;15000;12,5;Oui;01/01/2026;01/01/2026 00:30:00\r\n"
                        + "\"Sac; \"\"cuir\"\"\";;2500;;Non;03/02/2026;\r\n");
        assertThat(renderer.contentType(1)).isEqualTo("text/csv; charset=UTF-8");
        assertThat(renderer.extension(1)).isEqualTo("csv");
    }

    @Test
    @DisplayName("text starting with = + - @ TAB CR is prefixed with an apostrophe; numbers are not")
    void formulaInjectionGuard() throws Exception {
        List<ExportColumn> cols = List.of(new ExportColumn("t", "T", ExportColumnType.TEXT),
                new ExportColumn("n", "N", ExportColumnType.INTEGER));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = renderer.open(out, 1);
        s.beginDataset("x", "X", cols);
        for (String v : List.of("=SUM(A1)", "+cmd|calc", "-2+A1", "@cmd", "\tx", "\rx", "ok")) {
            s.row(List.of(v, -5L));
        }
        s.endDataset();
        s.close();

        String csv = new String(out.toByteArray(), StandardCharsets.UTF_8).substring(1);
        assertThat(csv.split("\r\n", -1)).startsWith("T;N", "'=SUM(A1);-5", "'+cmd|calc;-5", "'-2+A1;-5",
                "'@cmd;-5", "'\tx;-5");
        assertThat(csv).contains("\"'\rx\";-5\r\n").contains("\r\nok;-5\r\n");
    }

    @Test
    @DisplayName("numeric-like text (phone numbers, signed numbers) is not prefixed")
    void phoneNumbersNotPrefixed() throws Exception {
        List<ExportColumn> cols = List.of(new ExportColumn("p", "Téléphone", ExportColumnType.TEXT));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = renderer.open(out, 1);
        s.beginDataset("x", "X", cols);
        for (String v : List.of("+237 6 99 00 00 00", "+237699000000", "-2", "+(237) 699.00.00")) {
            s.row(List.of(v));
        }
        s.endDataset();
        s.close();

        String csv = new String(out.toByteArray(), StandardCharsets.UTF_8).substring(1);
        assertThat(csv).isEqualTo("Téléphone\r\n+237 6 99 00 00 00\r\n+237699000000\r\n-2\r\n+(237) 699.00.00\r\n");
    }

    @Test
    @DisplayName("several datasets: a zip with one <key>.csv per dataset, each with BOM + header")
    void severalDatasetsZip() throws Exception {
        List<ExportColumn> cols = List.of(new ExportColumn("n", "Nom", ExportColumnType.TEXT));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = renderer.open(out, 2);
        s.beginDataset("stores", "Boutiques", cols);
        s.row(List.of("A"));
        s.endDataset();
        s.beginDataset("products", "Produits", cols);
        s.row(List.of("B"));
        s.endDataset();
        s.close();

        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).containsOnlyKeys("stores.csv", "products.csv");
        assertThat(entries.get("stores.csv")).isEqualTo("﻿Nom\r\nA\r\n");
        assertThat(entries.get("products.csv")).isEqualTo("﻿Nom\r\nB\r\n");
        assertThat(renderer.contentType(2)).isEqualTo("application/zip");
        assertThat(renderer.extension(2)).isEqualTo("zip");
    }
}
