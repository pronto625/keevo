package com.keevo.reporting.export;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test provider generating {@code total} rows on demand (never materialised), recording the
 * largest page it was asked for and the number of page reads.
 */
public class SyntheticExportProvider implements ExportDatasetProvider {

    public static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final String key;
    private final String label;
    private final long total;
    public final AtomicInteger maxLimitRequested = new AtomicInteger();
    public final AtomicInteger maxRowsReturned = new AtomicInteger();
    public final AtomicInteger pageReads = new AtomicInteger();
    /** Filter of the last count / page read (criteria pass-through checks). */
    public volatile ExportFilter lastFilter;

    public SyntheticExportProvider(String key, String label, long total) {
        this.key = key;
        this.label = label;
        this.total = total;
    }

    @Override public String key() { return key; }
    @Override public String label() { return label; }

    @Override
    public List<ExportColumn> columns() {
        return List.of(
                new ExportColumn("seq", "N°", ExportColumnType.INTEGER),
                new ExportColumn("name", "Nom", ExportColumnType.TEXT),
                new ExportColumn("amount", "Montant", ExportColumnType.AMOUNT_XAF),
                new ExportColumn("at", "Date", ExportColumnType.DATETIME));
    }

    @Override
    public long count(ExportFilter filter) {
        lastFilter = filter;
        return total;
    }

    @Override
    public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
        pageReads.incrementAndGet();
        lastFilter = filter;
        maxLimitRequested.accumulateAndGet(limit, Math::max);
        long start = cursor == null ? 0 : Long.parseLong(cursor);
        long end = Math.min(total, start + limit);
        List<List<Object>> rows = new ArrayList<>((int) (end - start));
        for (long i = start; i < end; i++) {
            rows.add(List.of(i + 1, "Article " + (i + 1), (i + 1) * 100L, BASE.plusSeconds(i)));
        }
        maxRowsReturned.accumulateAndGet(rows.size(), Math::max);
        return new ExportPage(rows, end < total ? Long.toString(end) : null);
    }
}
