package com.keevo.reporting.export.adapter.out.render;

import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.port.out.ExportRenderer;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.infrastructure.export.ExportValues;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CSV renderer (Story v1s-18-1): UTF-8 with BOM, {@code ;} separator, CRLF, a header row of
 * column labels — opens directly in French-locale Excel. Several datasets produce a zip holding
 * one {@code <key>.csv} per dataset.
 *
 * <p>Cells: integers and XAF amounts as plain integers, decimals with a decimal comma (French
 * Excel), booleans {@code Oui}/{@code Non}, dates {@code dd/MM/yyyy} and datetimes
 * {@code dd/MM/yyyy HH:mm:ss} in WAT, text guarded against formula injection.
 */
@Component
public class CsvExportRenderer implements ExportRenderer {

    static final String CONTENT_TYPE_CSV = "text/csv; charset=UTF-8";
    static final String CONTENT_TYPE_ZIP = "application/zip";
    private static final char BOM = '﻿';

    private static final CSVFormat FORMAT = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setDelimiter(';')
            .setRecordSeparator("\r\n")
            .build();

    @Override
    public ExportFormat format() {
        return ExportFormat.CSV;
    }

    @Override
    public String contentType(int datasetCount) {
        return datasetCount > 1 ? CONTENT_TYPE_ZIP : CONTENT_TYPE_CSV;
    }

    @Override
    public String extension(int datasetCount) {
        return datasetCount > 1 ? "zip" : "csv";
    }

    @Override
    public RenderSession open(OutputStream out, int datasetCount) {
        return new CsvSession(out, datasetCount > 1);
    }

    private static final class CsvSession implements RenderSession {

        private final OutputStream target;
        private final ZipOutputStream zip;
        private CSVPrinter printer;
        private List<ExportColumn> columns;
        private final List<String> cells = new ArrayList<>();

        CsvSession(OutputStream out, boolean zipped) {
            OutputStream shielded = new NonClosingOutputStream(out);
            this.zip = zipped ? new ZipOutputStream(shielded, StandardCharsets.UTF_8) : null;
            this.target = zipped ? zip : shielded;
        }

        @Override
        public void beginDataset(String key, String label, List<ExportColumn> columns) throws IOException {
            if (zip != null) {
                String slug = ExportValues.slug(label); // French file name, never the dataset key (v1s-16-20)
                zip.putNextEntry(new ZipEntry((slug != null ? slug : "export") + ".csv"));
            }
            this.columns = columns;
            Writer writer = new OutputStreamWriter(new NonClosingOutputStream(target), StandardCharsets.UTF_8);
            writer.write(BOM);
            printer = new CSVPrinter(writer, FORMAT);
            for (ExportColumn c : columns) {
                printer.print(c.label());
            }
            printer.println();
        }

        @Override
        public void row(List<Object> values) throws IOException {
            cells.clear();
            for (int i = 0; i < columns.size(); i++) {
                Object v = i < values.size() ? values.get(i) : null;
                cells.add(format(columns.get(i), v));
            }
            printer.printRecord(cells);
        }

        @Override
        public void endDataset() throws IOException {
            printer.flush();
            printer.close(); // closes the writer only — the target is shielded
            printer = null;
            if (zip != null) {
                zip.closeEntry();
            }
        }

        @Override
        public void close() throws IOException {
            if (zip != null) {
                zip.finish();
            }
            target.flush();
        }

        private static String format(ExportColumn column, Object v) {
            if (v == null) return "";
            return switch (column.type()) {
                case TEXT -> ExportCells.safeText(v.toString());
                case INTEGER, AMOUNT_XAF -> Long.toString(ExportCells.toLong(v));
                case DECIMAL -> ExportCells.toDecimal(v).toPlainString().replace('.', ',');
                case BOOLEAN -> ExportCells.toBoolean(v) ? "Oui" : "Non";
                case DATE -> ExportCells.toWatDate(v).format(ExportCells.DATE);
                case DATETIME -> ExportCells.toWatDateTime(v).format(ExportCells.DATETIME);
            };
        }
    }

    /** Lets a writer / printer be closed without closing the response or file stream. */
    static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
