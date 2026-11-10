package com.keevo.whatsappcloud.template.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.5 — a message template of the company (Bref {@code owner_whatsapp_template}): DRAFT until Meta accepted it
 * for review, then Meta's status (PENDING, APPROVED, REJECTED with a reason, PAUSED, DISABLED). {@code submitError}
 * keeps Meta's last refusal of a submission (the template stays DRAFT). {@code system} = created by Keevo on the
 * account (the invoice template {@code bref_facture}), never listed among the owner's templates.
 */
public record MessageTemplate(UUID id, String name, String language, String category, String headerFormat,
                              String headerText, String headerHandle, String bodyText, List<String> bodyExamples,
                              String footerText, String status, String rejectedReason, String submitError,
                              String metaTemplateId, boolean system, UUID createdBy, Instant createdAt,
                              Instant updatedAt) {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";

    public MessageTemplate {
        bodyExamples = bodyExamples == null ? List.of() : List.copyOf(bodyExamples);
    }

    /** After a submission Meta accepted for review. */
    public MessageTemplate submitted(String metaId, String metaStatus, Instant now) {
        return new MessageTemplate(id, name, language, category, headerFormat, headerText, headerHandle, bodyText,
                bodyExamples, footerText, normalizeStatus(metaStatus), null, null, metaId, system, createdBy,
                createdAt, now);
    }

    /** After a submission Meta refused: stays DRAFT with the refusal. */
    public MessageTemplate submitFailed(String error, Instant now) {
        return new MessageTemplate(id, name, language, category, headerFormat, headerText, headerHandle, bodyText,
                bodyExamples, footerText, DRAFT, rejectedReason, error, metaTemplateId, system, createdBy, createdAt,
                now);
    }

    /** Meta's statuses Keevo stores; anything else (IN_APPEAL, LIMIT_EXCEEDED…) reads as PENDING. */
    public static String normalizeStatus(String metaStatus) {
        if (metaStatus == null) {
            return PENDING;
        }
        return switch (metaStatus.toUpperCase(java.util.Locale.ROOT)) {
            case "APPROVED" -> APPROVED;
            case "REJECTED" -> "REJECTED";
            case "PAUSED" -> "PAUSED";
            case "DISABLED" -> "DISABLED";
            default -> PENDING;
        };
    }
}
