package com.keevo.reporting.export.application.service;

import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.port.out.DocumentExportRenderer;
import com.keevo.reporting.export.domain.port.out.ExportRenderer;
import com.keevo.reporting.export.domain.port.out.ExportRenderer.RenderSession;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams datasets × keyset pages into a {@link RenderSession} (Story v1s-18-1). Holds at most one
 * page in memory; every page is read in its own provider transaction.
 */
@Component
public class ExportStreamingWriter {

    private final int pageSize;

    @Autowired
    public ExportStreamingWriter(ExportProperties properties) {
        this(properties.getPageSize());
    }

    public ExportStreamingWriter(int pageSize) {
        if (pageSize < 1) throw new IllegalArgumentException("keevo.export.page-size must be >= 1");
        this.pageSize = pageSize;
    }

    /**
     * @return total data rows written
     * @throws IOException on write failure (the session is aborted, the target is left as is)
     */
    public long write(OutputStream out, ExportRenderer renderer, List<ExportDatasetProvider> providers,
                      ExportFilter filter) throws IOException {
        return write(out, renderer, providers, filter, new AtomicLong());
    }

    /**
     * Same as {@link #write(OutputStream, ExportRenderer, List, ExportFilter)}, reporting every row
     * handed to the renderer in {@code progress} — readable after a failure (rows written so far).
     */
    public long write(OutputStream out, ExportRenderer renderer, List<ExportDatasetProvider> providers,
                      ExportFilter filter, AtomicLong progress) throws IOException {
        if (renderer instanceof DocumentExportRenderer document) {
            // Story v1s-18-3 — a document (PDF) reads its providers itself, bounded by its limits.
            return document.render(out, providers, filter, progress);
        }
        RenderSession session = renderer.open(out, providers.size());
        long total = 0;
        try {
            for (ExportDatasetProvider provider : providers) {
                session.beginDataset(provider.key(), provider.label(), provider.columns());
                String cursor = null;
                do {
                    ExportPage page = provider.readPage(filter, cursor, pageSize);
                    if (page.rows().size() > pageSize) {
                        throw new IllegalStateException("Export provider '" + provider.key()
                                + "' returned more rows than the page size");
                    }
                    for (List<Object> row : page.rows()) {
                        session.row(row);
                        total++;
                        progress.incrementAndGet();
                    }
                    String next = page.nextCursor();
                    if (next != null && Objects.equals(next, cursor)) {
                        throw new IllegalStateException("Export provider '" + provider.key()
                                + "' returned the same cursor twice");
                    }
                    cursor = next;
                } while (cursor != null);
                session.endDataset();
            }
        } catch (IOException | RuntimeException e) {
            session.abort();
            throw e;
        }
        session.close();
        return total;
    }
}
