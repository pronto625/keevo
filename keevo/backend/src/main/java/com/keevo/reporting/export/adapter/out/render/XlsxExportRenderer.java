package com.keevo.reporting.export.adapter.out.render;

import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.port.out.ExportRenderer;
import com.keevo.shared.application.port.export.ExportColumn;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * XLSX renderer (Story v1s-18-1) on Apache POI SXSSF: a 100-row window in memory, older rows are
 * flushed to a temporary file, {@code dispose()} always runs. One sheet per dataset (name = label,
 * sanitised, ≤ 31 chars, unique), bold frozen header row, numeric cells, {@code #,##0 "XAF"}
 * amounts, {@code dd/mm/yyyy} dates and {@code dd/mm/yyyy hh:mm:ss} datetimes in WAT, text as-is
 * (string cells are never evaluated; cut at 32,767 chars). A dataset past
 * 1,048,575 rows continues in {@code <label> (2)}, {@code (3)}…
 */
@Component
public class XlsxExportRenderer implements ExportRenderer {

    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final int WINDOW_ROWS = 100;
    /** Data rows per sheet: Excel's row limit minus the header row. */
    static final int MAX_DATA_ROWS = SpreadsheetVersion.EXCEL2007.getMaxRows() - 1;
    private static final int SHEET_NAME_MAX = 31;
    /** Excel's cell text limit; longer text would make POI throw and kill the whole export. */
    static final int MAX_TEXT_LENGTH = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
    private static final int COLUMN_WIDTH_CHARS = 20;

    private final int maxDataRowsPerSheet;

    @Autowired
    public XlsxExportRenderer() {
        this(MAX_DATA_ROWS);
    }

    /** Tests: a small sheet limit exercises the overflow without a million rows. */
    XlsxExportRenderer(int maxDataRowsPerSheet) {
        this.maxDataRowsPerSheet = maxDataRowsPerSheet;
    }

    @Override
    public ExportFormat format() {
        return ExportFormat.XLSX;
    }

    @Override
    public String contentType(int datasetCount) {
        return CONTENT_TYPE;
    }

    @Override
    public String extension(int datasetCount) {
        return "xlsx";
    }

    @Override
    public RenderSession open(OutputStream out, int datasetCount) {
        return new XlsxSession(out, maxDataRowsPerSheet);
    }

    private static final class XlsxSession implements RenderSession {

        private final OutputStream out;
        private final int maxRows;
        private final SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW_ROWS);
        private final CellStyle headerStyle;
        private final CellStyle amountStyle;
        private final CellStyle dateStyle;
        private final CellStyle dateTimeStyle;
        private final Set<String> usedNames = new HashSet<>();

        private List<ExportColumn> columns;
        private String label;
        private int part;
        private SXSSFSheet sheet;
        private int rowIndex;
        private boolean finished;

        XlsxSession(OutputStream out, int maxRows) {
            this.out = out;
            this.maxRows = maxRows;
            workbook.setCompressTempFiles(true);
            DataFormat fmt = workbook.createDataFormat();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle = workbook.createCellStyle();
            headerStyle.setFont(bold);
            amountStyle = workbook.createCellStyle();
            amountStyle.setDataFormat(fmt.getFormat("#,##0 \"XAF\""));
            dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(fmt.getFormat("dd/mm/yyyy"));
            dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(fmt.getFormat("dd/mm/yyyy hh:mm:ss"));
        }

        @Override
        public void beginDataset(String key, String label, List<ExportColumn> columns) {
            this.columns = columns;
            this.label = label != null && !label.isBlank() ? label : key;
            this.part = 1;
            newSheet();
        }

        private void newSheet() {
            String base = part == 1 ? label : label + " (" + part + ")";
            sheet = workbook.createSheet(uniqueName(base));
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i).label());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, COLUMN_WIDTH_CHARS * 256);
            }
            sheet.createFreezePane(0, 1);
            rowIndex = 1;
        }

        private String uniqueName(String wanted) {
            String safe = truncate(WorkbookUtil.createSafeSheetName(wanted), SHEET_NAME_MAX);
            String candidate = safe;
            int n = 2;
            while (usedNames.contains(candidate.toLowerCase(Locale.ROOT))) {
                String suffix = " (" + n++ + ")";
                candidate = truncate(safe, SHEET_NAME_MAX - suffix.length()) + suffix;
            }
            usedNames.add(candidate.toLowerCase(Locale.ROOT));
            return candidate;
        }

        private static String truncate(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max);
        }

        @Override
        public void row(List<Object> values) {
            if (rowIndex > maxRows) {
                part++;
                newSheet();
            }
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                Object v = i < values.size() ? values.get(i) : null;
                if (v == null) continue;
                write(row.createCell(i), columns.get(i), v);
            }
        }

        private void write(Cell cell, ExportColumn column, Object v) {
            switch (column.type()) {
                case TEXT -> cell.setCellValue(truncate(v.toString(), MAX_TEXT_LENGTH)); // never evaluated: as-is
                case INTEGER -> cell.setCellValue((double) ExportCells.toLong(v));
                case AMOUNT_XAF -> {
                    cell.setCellValue((double) ExportCells.toLong(v));
                    cell.setCellStyle(amountStyle);
                }
                case DECIMAL -> cell.setCellValue(ExportCells.toDecimal(v).doubleValue());
                case BOOLEAN -> cell.setCellValue(ExportCells.toBoolean(v));
                case DATE -> {
                    cell.setCellValue(ExportCells.toWatDate(v));
                    cell.setCellStyle(dateStyle);
                }
                case DATETIME -> {
                    cell.setCellValue(ExportCells.toWatDateTime(v));
                    cell.setCellStyle(dateTimeStyle);
                }
            }
        }

        @Override
        public void endDataset() {
            sheet = null;
        }

        @Override
        public void close() throws IOException {
            if (finished) return;
            finished = true;
            try {
                workbook.write(new CsvExportRenderer.NonClosingOutputStream(out));
                out.flush();
            } finally {
                workbook.dispose();
                workbook.close();
            }
        }

        @Override
        public void abort() {
            if (finished) return;
            finished = true;
            try {
                workbook.dispose();
                workbook.close();
            } catch (IOException | RuntimeException ignored) {
                // best effort: temp files are already removed by dispose()
            }
        }
    }
}
