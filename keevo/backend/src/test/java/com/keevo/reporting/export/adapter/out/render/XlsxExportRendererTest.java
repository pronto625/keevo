package com.keevo.reporting.export.adapter.out.render;

import com.keevo.reporting.export.domain.port.out.ExportRenderer.RenderSession;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("XlsxExportRenderer (v1s-18-1)")
class XlsxExportRendererTest {

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("qty", "Quantité", ExportColumnType.INTEGER),
            new ExportColumn("amount", "Montant", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("active", "Actif", ExportColumnType.BOOLEAN),
            new ExportColumn("day", "Jour", ExportColumnType.DATE),
            new ExportColumn("at", "Le", ExportColumnType.DATETIME));

    @Test
    @DisplayName("one sheet per dataset, bold frozen header, numeric cells, XAF / date / datetime formats in WAT")
    void sheetsHeaderAndFormats() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = new XlsxExportRenderer().open(out, 2);
        s.beginDataset("stores", "Boutiques", COLUMNS);
        s.row(List.of("=cmd", 3L, 15000L, true, LocalDate.of(2026, 2, 3), Instant.parse("2025-12-31T23:30:00Z")));
        s.row(Arrays.asList("Sac", null, null, null, null, null));
        s.endDataset();
        s.beginDataset("products", "Produits", COLUMNS);
        s.endDataset();
        s.close();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            XSSFSheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Boutiques");
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("Produits");

            PaneInformation pane = sheet.getPaneInformation();
            assertThat(pane).isNotNull();
            assertThat(pane.isFreezePane()).isTrue();
            assertThat(pane.getHorizontalSplitPosition()).isEqualTo((short) 1);

            Row header = sheet.getRow(0);
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Quantité");
            assertThat(wb.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();

            Row r = sheet.getRow(1);
            assertThat(r.getCell(0).getStringCellValue()).as("XLSX text is never evaluated: as-is").isEqualTo("=cmd");
            assertThat(r.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(r.getCell(1).getNumericCellValue()).isEqualTo(3d);
            assertThat(r.getCell(2).getNumericCellValue()).isEqualTo(15000d);
            assertThat(r.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("#,##0 \"XAF\"");
            assertThat(r.getCell(3).getBooleanCellValue()).isTrue();
            assertThat(r.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
            assertThat(r.getCell(4).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 2, 3, 0, 0));
            assertThat(r.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy hh:mm:ss");
            assertThat(r.getCell(5).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 30));

            assertThat(sheet.getRow(2).getCell(1)).isNull();
        }
        XlsxExportRenderer renderer = new XlsxExportRenderer();
        assertThat(renderer.contentType(1))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(renderer.extension(3)).isEqualTo("xlsx");
    }

    @Test
    @DisplayName("sheet names: sanitised, at most 31 chars, unique")
    void sheetNames() throws Exception {
        List<ExportColumn> cols = List.of(new ExportColumn("n", "N", ExportColumnType.TEXT));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = new XlsxExportRenderer().open(out, 3);
        String longLabel = "Mouvements de stock / entrées [détail] et sorties";
        s.beginDataset("a", longLabel, cols);
        s.endDataset();
        s.beginDataset("b", longLabel, cols);
        s.endDataset();
        s.beginDataset("c", "Boutiques", cols);
        s.endDataset();
        s.close();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            String first = wb.getSheetName(0);
            String second = wb.getSheetName(1);
            assertThat(first).hasSizeLessThanOrEqualTo(31).doesNotContain("/", "[", "]");
            assertThat(second).hasSizeLessThanOrEqualTo(31).endsWith(" (2)");
            assertThat(first).isNotEqualTo(second);
            assertThat(wb.getSheetName(2)).isEqualTo("Boutiques");
        }
    }

    @Test
    @DisplayName("a dataset past the sheet row limit continues in '<label> (2)' with its own header")
    void overflowContinuesInNextSheet() throws Exception {
        List<ExportColumn> cols = List.of(new ExportColumn("n", "N", ExportColumnType.INTEGER));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = new XlsxExportRenderer(3).open(out, 1);
        s.beginDataset("x", "Ventes", cols);
        for (long i = 1; i <= 7; i++) s.row(List.of(i));
        s.endDataset();
        s.close();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetName(0)).isEqualTo("Ventes");
            assertThat(wb.getSheetName(1)).isEqualTo("Ventes (2)");
            assertThat(wb.getSheetName(2)).isEqualTo("Ventes (3)");
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            assertThat(wb.getSheetAt(1).getRow(0).getCell(0).getStringCellValue()).isEqualTo("N");
            assertThat(wb.getSheetAt(1).getRow(1).getCell(0).getNumericCellValue()).isEqualTo(4d);
            assertThat(wb.getSheetAt(2).getLastRowNum()).isEqualTo(1);
            assertThat(wb.getSheetAt(2).getRow(1).getCell(0).getNumericCellValue()).isEqualTo(7d);
        }
    }

    @Test
    @DisplayName("text longer than 32,767 chars is cut instead of failing the export")
    void longTextTruncated() throws Exception {
        List<ExportColumn> cols = List.of(new ExportColumn("t", "T", ExportColumnType.TEXT));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RenderSession s = new XlsxExportRenderer().open(out, 1);
        s.beginDataset("x", "X", cols);
        s.row(List.of("a".repeat(40_000)));
        s.endDataset();
        s.close();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).hasSize(32_767);
        }
    }

    @Test
    @DisplayName("the production sheet limit is Excel's 1,048,575 data rows")
    void productionLimit() {
        assertThat(XlsxExportRenderer.MAX_DATA_ROWS).isEqualTo(1_048_575);
    }
}
