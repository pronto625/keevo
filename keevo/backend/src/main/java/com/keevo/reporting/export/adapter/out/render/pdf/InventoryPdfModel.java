package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.infrastructure.pdf.PdfFormats;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model of the « Rapport d'inventaire » PDF (Story v1s-18-3) from the {@code inventory_counts}
 * dataset, whose keyset order (session started_at, session id) groups rows by session: one block
 * per session with theoretical / counted / gap per line (non-zero gaps highlighted) and totals over
 * the counted lines (uncounted lines show « — »).
 */
public final class InventoryPdfModel {

    static final String NOT_COUNTED = "—";

    public record InventoryLine(String product, String variant, String theoretical, String counted, String gap,
                                String countedBy, boolean highlighted) {
    }

    public record SessionBlock(String title, String subtitle, List<InventoryLine> lines, String theoretical,
                               String counted, String gap, String gapLines) {
    }

    private InventoryPdfModel() {
    }

    /** Running totals of one session. */
    private static final class Acc {
        final String sessionId;
        final String title;
        final String subtitle;
        final List<InventoryLine> lines = new ArrayList<>();
        long theoretical;
        long counted;
        long gap;
        long gapLines;

        Acc(String sessionId, String title, String subtitle) {
            this.sessionId = sessionId;
            this.title = title;
            this.subtitle = subtitle;
        }

        SessionBlock block() {
            return new SessionBlock(title, subtitle, lines, PdfFormats.integer(theoretical),
                    PdfFormats.integer(counted), signed(gap), PdfFormats.integer(gapLines));
        }
    }

    static Map<String, Object> build(ExportDatasetProvider provider, ExportFilter filter, PdfHeader header,
                                     int pageSize, int limit, AtomicLong progress) {
        List<Acc> sessions = new ArrayList<>();
        PdfRowReader.Result result = PdfRowReader.read(provider, filter, pageSize, limit, progress, row -> {
            String sessionId = row.text("sessionId");
            Acc acc = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
            if (acc == null || !Objects.equals(acc.sessionId, sessionId)) {
                String subtitle = row.text("store") + (row.text("sessionStatus").isEmpty() ? ""
                        : " · " + row.text("sessionStatus"));
                acc = new Acc(sessionId, "Inventaire du " + PdfFormats.dateTime(row.instant("sessionStartedAt")),
                        subtitle);
                sessions.add(acc);
            }
            Long theoretical = row.longValue("theoretical");
            Long physical = row.longValue("physical");
            Long gap = row.longValue("gap");
            if (gap == null && theoretical != null && physical != null) gap = physical - theoretical;
            if (physical != null) { // totals cover the counted lines only (theoretical − counted = gap)
                acc.theoretical += theoretical != null ? theoretical : 0;
                acc.counted += physical;
                acc.gap += gap != null ? gap : 0;
                if (gap != null && gap != 0) acc.gapLines++;
            }
            acc.lines.add(new InventoryLine(row.text("product"), row.text("variant"),
                    theoretical == null ? "" : PdfFormats.integer(theoretical),
                    physical == null ? NOT_COUNTED : PdfFormats.integer(physical),
                    physical == null || gap == null ? NOT_COUNTED : signed(gap),
                    row.text("countedBy"), physical != null && gap != null && gap != 0));
        });
        Map<String, Object> vars = PdfModels.base(header, sessions.isEmpty(), result.truncated(), limit, "lignes");
        vars.put("sessions", sessions.stream().map(Acc::block).toList());
        return vars;
    }

    /** Gap with an explicit sign: {@code +2}, {@code -3}, {@code 0}. */
    static String signed(long gap) {
        return gap > 0 ? "+" + PdfFormats.integer(gap) : PdfFormats.integer(gap);
    }
}
