package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.infrastructure.pdf.PdfDocumentFactory;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.port.out.DocumentExportRenderer;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreView;
import com.keevo.store.api.StoreQueryPort;
import org.springframework.stereotype.Component;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * PDF renderer (Story v1s-18-3, FR95): branded A4 documents « Fin de journée », « Rapport
 * d'inventaire », « État de stock », « Liste des ventes », chosen by the single requested dataset
 * ({@link PdfTemplate}). PDF is Premium ({@code EXPORT_PDF}, gated by the export service).
 *
 * <p>Bounded: a document holds at most {@code keevo.export.pdf-max-rows} data rows (the end-of-day
 * one {@code pdf-max-closures} closures); a larger request is refused before any quota is used
 * (422 {@code EXPORT_PDF_TOO_LARGE}), and rows arriving beyond the limit while rendering are not
 * read — the document then says it is cut.
 */
@Component
public class PdfExportRenderer implements DocumentExportRenderer {

    static final String CONTENT_TYPE = "application/pdf";

    private final PdfDocumentFactory documents;
    private final EndOfDayPdfModel endOfDay;
    private final CompanyIdentity companyIdentity;
    private final StoreQueryPort storeQuery;
    private final Clock clock;
    private final int pageSize;
    private final int maxRows;
    private final int maxClosures;

    public PdfExportRenderer(PdfDocumentFactory documents, EndOfDayPdfModel endOfDay,
                             CompanyIdentity companyIdentity, StoreQueryPort storeQuery,
                             ExportProperties properties, Clock clock) {
        this.documents = documents;
        this.endOfDay = endOfDay;
        this.companyIdentity = companyIdentity;
        this.storeQuery = storeQuery;
        this.clock = clock;
        this.pageSize = properties.getPageSize();
        this.maxRows = properties.getPdfMaxRows();
        this.maxClosures = properties.getPdfMaxClosures();
    }

    @Override
    public ExportFormat format() {
        return ExportFormat.PDF;
    }

    @Override
    public String contentType(int datasetCount) {
        return CONTENT_TYPE;
    }

    @Override
    public String extension(int datasetCount) {
        return "pdf";
    }

    @Override
    public Optional<String> templateLabel(String datasetKey) {
        return PdfTemplate.forDataset(datasetKey).map(PdfTemplate::label);
    }

    @Override
    public void validate(List<ExportDatasetProvider> providers) {
        template(providers);
    }

    @Override
    public void checkSize(List<ExportDatasetProvider> providers, ExportFilter filter, long countedRows) {
        PdfTemplate template = template(providers);
        int limit = limit(template);
        if (countedRows > limit) {
            throw new DomainException(ErrorCode.EXPORT_PDF_TOO_LARGE,
                    "PDF would hold " + countedRows + " rows, limit " + limit,
                    Map.of("limit", limit, "rows", countedRows, "unit", unit(template)));
        }
    }

    @Override
    public long render(OutputStream out, List<ExportDatasetProvider> providers, ExportFilter filter,
                       AtomicLong progress) throws IOException {
        PdfTemplate template = template(providers);
        ExportDatasetProvider provider = providers.get(0);
        Instant now = Instant.now(clock);
        String schema = TenantContext.getCurrentTenant();
        PdfHeader header = PdfHeader.of(template, businessName(schema), logo(schema), storeName(filter), filter,
                template == PdfTemplate.STOCK, now);
        long before = progress.get();
        int limit = limit(template);
        Map<String, Object> vars = switch (template) {
            case END_OF_DAY -> endOfDay.build(provider, filter, header, pageSize, limit, progress);
            case INVENTORY -> InventoryPdfModel.build(provider, filter, header, pageSize, limit, progress);
            case STOCK -> StockPdfModel.build(provider, filter, header, pageSize, limit, progress);
            case SALES -> SalesPdfModel.build(provider, filter, header, pageSize, limit, progress);
        };
        documents.render(template.templateName(), vars, new NonClosingOutputStream(out));
        out.flush();
        return progress.get() - before;
    }

    /** French unit of the limit, in the 422 message and the truncation note. */
    static String unit(PdfTemplate template) {
        return template.limitedByClosures() ? "clôtures" : "lignes";
    }

    private int limit(PdfTemplate template) {
        return template.limitedByClosures() ? maxClosures : maxRows;
    }

    /** The one template of the request; any other shape → 400 EXPORT_FORMAT_UNSUPPORTED. */
    private static PdfTemplate template(List<ExportDatasetProvider> providers) {
        Optional<PdfTemplate> template = providers.size() == 1
                ? PdfTemplate.forDataset(providers.get(0).key())
                : Optional.empty();
        return template.orElseThrow(() -> new DomainException(ErrorCode.EXPORT_FORMAT_UNSUPPORTED,
                "No PDF document for these datasets",
                Map.of("format", ExportFormat.PDF.name(),
                        "dataset", providers.stream().map(ExportDatasetProvider::key).collect(Collectors.joining(",")))));
    }

    /** Story 14.2 — the company designation when filled in, else the shop name (tenant name). */
    private String businessName(String schema) {
        if (schema == null) return null;
        return companyIdentity.displayName(schema);
    }

    /** Story 14.2 — the company logo as a {@code data:} URI, {@code null} without logo (never fails the export). */
    private String logo(String schema) {
        if (schema == null) return null;
        try {
            return companyIdentity.logo(schema).map(CompanyIdentity.Logo::dataUri).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String storeName(ExportFilter filter) {
        if (filter.storeId() == null) return null;
        return storeQuery.findById(TenantContext.getCurrentTenant(), filter.storeId()).map(StoreView::name).orElse("Boutique");
    }

    /** Keeps the response / job file stream open when the PDF writer closes its output. */
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
