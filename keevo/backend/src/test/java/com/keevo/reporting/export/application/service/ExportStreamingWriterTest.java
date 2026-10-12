package com.keevo.reporting.export.application.service;

import com.keevo.reporting.export.SyntheticExportProvider;
import com.keevo.reporting.export.adapter.out.render.CsvExportRenderer;
import com.keevo.reporting.export.adapter.out.render.XlsxExportRenderer;
import com.keevo.reporting.export.domain.port.out.ExportRenderer;
import com.keevo.shared.application.port.export.ExportFilter;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExportStreamingWriter (v1s-18-1)")
class ExportStreamingWriterTest {

    private static final int ROWS = 50_000;
    private static final int PAGE = 1000;

    @Test
    @DisplayName("50,000 rows to CSV: every row written, provider read one bounded page at a time")
    void fiftyThousandRowsCsv() throws Exception {
        SyntheticExportProvider provider = new SyntheticExportProvider("sales", "Ventes", ROWS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long written = new ExportStreamingWriter(PAGE)
                .write(out, new CsvExportRenderer(), List.of(provider), ExportFilter.none());

        assertThat(written).isEqualTo(ROWS);
        assertThat(provider.maxLimitRequested.get()).isEqualTo(PAGE);
        assertThat(provider.maxRowsReturned.get()).isLessThanOrEqualTo(PAGE);
        assertThat(provider.pageReads.get()).isEqualTo(ROWS / PAGE);
        long lines;
        String last = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8))) {
            lines = 0;
            String line;
            while ((line = r.readLine()) != null) {
                lines++;
                last = line;
            }
        }
        assertThat(lines).isEqualTo(ROWS + 1L); // header + data rows
        assertThat(last).startsWith("50000;Article 50000;5000000;");
    }

    @Test
    @DisplayName("50,000 rows to XLSX: every row written, provider read one bounded page at a time")
    void fiftyThousandRowsXlsx() throws Exception {
        SyntheticExportProvider provider = new SyntheticExportProvider("sales", "Ventes", ROWS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long written = new ExportStreamingWriter(PAGE)
                .write(out, new XlsxExportRenderer(), List.of(provider), ExportFilter.none());

        assertThat(written).isEqualTo(ROWS);
        assertThat(provider.maxLimitRequested.get()).isEqualTo(PAGE);
        assertThat(provider.maxRowsReturned.get()).isLessThanOrEqualTo(PAGE);
        assertThat(countXlsxRows(out.toByteArray())).isEqualTo(ROWS + 1); // header + data rows
    }

    @Test
    @DisplayName("several datasets: rows of all datasets are counted, in order")
    void severalDatasets() throws Exception {
        var a = new SyntheticExportProvider("a", "A", 3);
        var b = new SyntheticExportProvider("b", "B", 2500);
        long written = new ExportStreamingWriter(PAGE)
                .write(new ByteArrayOutputStream(), new CsvExportRenderer(), List.of(a, b), ExportFilter.none());
        assertThat(written).isEqualTo(2503);
        assertThat(b.pageReads.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("a failing provider aborts the session (no close) and propagates")
    void failureAbortsSession() throws Exception {
        ExportRenderer renderer = mock(ExportRenderer.class);
        ExportRenderer.RenderSession session = mock(ExportRenderer.RenderSession.class);
        when(renderer.open(any(OutputStream.class), anyInt())).thenReturn(session);
        var provider = mock(com.keevo.shared.application.port.export.ExportDatasetProvider.class);
        when(provider.key()).thenReturn("x");
        when(provider.readPage(any(), any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> new ExportStreamingWriter(PAGE)
                .write(new ByteArrayOutputStream(), renderer, List.of(provider), ExportFilter.none()))
                .hasMessage("db down");
        verify(session).abort();
        verify(session, never()).close();
    }

    @Test
    @DisplayName("a renderer write failure aborts the session")
    void writeFailureAborts() throws Exception {
        ExportRenderer renderer = mock(ExportRenderer.class);
        ExportRenderer.RenderSession session = mock(ExportRenderer.RenderSession.class);
        when(renderer.open(any(OutputStream.class), anyInt())).thenReturn(session);
        doThrow(new java.io.IOException("broken pipe")).when(session).row(any());

        assertThatThrownBy(() -> new ExportStreamingWriter(PAGE).write(new ByteArrayOutputStream(), renderer,
                List.of(new SyntheticExportProvider("x", "X", 5)), ExportFilter.none()))
                .hasMessage("broken pipe");
        verify(session).abort();
    }

    /** Count {@code <row} elements of every sheet with the streaming reader (no DOM). */
    private static int countXlsxRows(byte[] xlsx) throws Exception {
        Pattern rowTag = Pattern.compile("<row[ >]");
        int rows = 0;
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(xlsx))) {
            Iterator<InputStream> sheets = new XSSFReader(pkg).getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream in = sheets.next()) {
                    String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = rowTag.matcher(xml);
                    while (m.find()) rows++;
                }
            }
        }
        return rows;
    }
}
