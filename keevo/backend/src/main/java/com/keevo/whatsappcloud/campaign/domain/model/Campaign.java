package com.keevo.whatsappcloud.campaign.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.6 — a WhatsApp campaign of the company to its consenting clients (Bref {@code customer_campaign}).
 * {@code storeIds} empty = every store. The template is chosen by id while the campaign is editable; when the send
 * starts its name, language, body and header format are frozen in the {@code template*} fields (a template deleted
 * afterwards does not change what leaves). {@code headerMediaId} / {@code headerMediaLink}: the media of a media
 * template.
 */
public record Campaign(UUID id, String name, String description, CampaignStatus status, List<UUID> storeIds,
                       UUID templateId, List<VariableMapping> variables, String headerMediaId, String headerMediaLink,
                       Instant scheduledAt, Instant startedAt, Instant sentAt, Counters counters,
                       FrozenTemplate frozen, UUID createdBy, Instant createdAt, Instant updatedAt,
                       PurchaseCriteria criteria) {

    public Campaign {
        criteria = criteria == null ? PurchaseCriteria.NONE : criteria;
        storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
        variables = variables == null ? List.of() : List.copyOf(variables);
        counters = counters == null ? Counters.ZERO : counters;
    }

    /** What the campaign reached: Meta's statuses move sent → delivered → read; failed, skipped, uncertain. */
    public record Counters(int targeted, int sent, int delivered, int read, int failed, int skipped, int uncertain) {
        public static final Counters ZERO = new Counters(0, 0, 0, 0, 0, 0, 0);
    }

    /** The template as it was when the send started. */
    public record FrozenTemplate(String name, String language, String bodyText, String headerFormat) {
    }
}
