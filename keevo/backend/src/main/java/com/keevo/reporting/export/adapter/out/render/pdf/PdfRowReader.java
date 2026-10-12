package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Reads a provider by keyset pages for a PDF (Story v1s-18-3), handing each row to a consumer as a
 * {@link Row} addressed by column KEY (never by position). Stops after {@code limit} rows: the
 * rows beyond are never buffered, and {@link Result#truncated()} says the document is cut.
 */
final class PdfRowReader {

    private PdfRowReader() {
    }

    /** One provider row, values read by column key. */
    static final class Row {
        private final Map<String, Integer> index;
        private final List<Object> values;

        Row(Map<String, Integer> index, List<Object> values) {
            this.index = index;
            this.values = values;
        }

        Object get(String key) {
            Integer i = index.get(key);
            if (i == null) throw new IllegalStateException("Unknown export column '" + key + "'");
            return i < values.size() ? values.get(i) : null;
        }

        String text(String key) { return PdfFormats.text(get(key)); }
        Long longValue(String key) { return PdfFormats.toLong(get(key)); }
        long longOrZero(String key) { return PdfFormats.toLongOrZero(get(key)); }
        java.time.Instant instant(String key) { return PdfFormats.toInstant(get(key)); }
    }

    record Result(long rows, boolean truncated) {
    }

    static Result read(ExportDatasetProvider provider, ExportFilter filter, int pageSize, int limit,
                       AtomicLong progress, Consumer<Row> consumer) {
        Map<String, Integer> index = new HashMap<>();
        List<ExportColumn> columns = provider.columns();
        for (int i = 0; i < columns.size(); i++) {
            index.put(columns.get(i).key(), i);
        }
        long read = 0;
        String cursor = null;
        do {
            ExportPage page = provider.readPage(filter, cursor, pageSize);
            for (List<Object> values : page.rows()) {
                if (read >= limit) {
                    return new Result(read, true);
                }
                consumer.accept(new Row(index, values));
                read++;
                progress.incrementAndGet();
            }
            String next = page.nextCursor();
            if (next != null && Objects.equals(next, cursor)) {
                throw new IllegalStateException("Export provider '" + provider.key()
                        + "' returned the same cursor twice");
            }
            cursor = next;
        } while (cursor != null);
        return new Result(read, false);
    }
}
