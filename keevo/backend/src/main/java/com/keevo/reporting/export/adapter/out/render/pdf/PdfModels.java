package com.keevo.reporting.export.adapter.out.render.pdf;

import com.keevo.shared.infrastructure.pdf.PdfFormats;
import java.util.HashMap;
import java.util.Map;

/** Variables shared by every PDF template (Story v1s-18-3): header, empty state, truncation note. */
final class PdfModels {

    private PdfModels() {
    }

    static Map<String, Object> base(PdfHeader header, boolean empty, boolean truncated, int limit, String limitUnit) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("header", header);
        vars.put("empty", empty);
        vars.put("truncated", truncated);
        vars.put("limitText", PdfFormats.integer(limit));
        vars.put("limitUnit", limitUnit);
        return vars;
    }
}
