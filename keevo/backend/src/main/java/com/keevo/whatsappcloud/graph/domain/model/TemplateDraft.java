package com.keevo.whatsappcloud.graph.domain.model;

import java.util.List;

/**
 * Story 21.5 — a message template to submit to Meta (Bref's {@code CreateWhatsAppTemplateDto}): {@code headerFormat}
 * NONE | TEXT | IMAGE | VIDEO | DOCUMENT; a media header carries Meta's upload handle; one example per body variable.
 */
public record TemplateDraft(String name, String category, String language, String headerFormat, String headerText,
                            String headerHandle, String bodyText, List<String> bodyExamples, String footerText) {

    public TemplateDraft {
        bodyExamples = bodyExamples == null ? List.of() : List.copyOf(bodyExamples);
    }
}
