package com.keevo.whatsappcloud.template.domain.model;

/**
 * Story 21.5 — Meta's review result of a template (webhook {@code message_template_status_update} or a refresh from
 * the template list): matched by Meta's id, else by name and language.
 */
public record TemplateReview(String metaTemplateId, String name, String language, String status,
                             String rejectedReason) {
}
