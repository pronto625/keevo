package com.keevo.migration.adapter.out.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.migration.domain.model.BrefAnnexes.AiConversation;
import com.keevo.migration.domain.model.BrefAnnexes.Campaign;
import com.keevo.migration.domain.model.BrefAnnexes.CampaignMessage;
import com.keevo.migration.domain.model.BrefAnnexes.ChatMessage;
import com.keevo.migration.domain.model.BrefAnnexes.Content;
import com.keevo.migration.domain.model.BrefAnnexes.Notification;
import com.keevo.migration.domain.model.BrefAnnexes.Targeting;
import com.keevo.migration.domain.model.BrefAnnexes.Template;
import com.keevo.migration.domain.model.BrefAnnexes.Variable;
import com.keevo.migration.domain.model.BrefAnnexes.WebPushSubscription;
import com.keevo.migration.domain.model.BrefAnnexes.WhatsAppAccount;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static com.keevo.migration.adapter.out.source.BrefRows.bool;
import static com.keevo.migration.adapter.out.source.BrefRows.instant;
import static com.keevo.migration.adapter.out.source.BrefRows.instantOrNull;
import static com.keevo.migration.adapter.out.source.BrefRows.text;
import static com.keevo.migration.adapter.out.source.BrefRows.uuid;

/**
 * Story 24.6 — the mapping of the annex rows (JSON with the Bref property names: the export's objects and the
 * database's {@code to_jsonb(row)}), shared by both section sources so they cannot drift. JSON columns become canonical
 * JSON text (object keys sorted recursively): jsonb and the export order keys differently, the text and the checksums
 * must not.
 */
final class BrefAnnexRows {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private BrefAnnexRows() {
    }

    static WhatsAppAccount whatsappAccount(JsonNode n) {
        return new WhatsAppAccount(uuid(n, "id"), uuidOrNull(n, "ownerId"), text(n, "wabaId"),
                text(n, "phoneNumberId"), text(n, "displayPhoneNumber"), text(n, "verifiedName"),
                text(n, "accessToken"), text(n, "status"), instant(n, "createdAt"), instantOrNull(n, "updatedAt"));
    }

    static Template template(JsonNode n) {
        List<String> examples = new ArrayList<>();
        JsonNode e = n.get("bodyExamples");
        if (e != null && e.isArray()) {
            e.forEach(x -> examples.add(x.isNull() ? "" : x.asText()));
        }
        return new Template(uuid(n, "id"), uuidOrNull(n, "ownerId"), text(n, "name"), text(n, "category"),
                text(n, "language"), text(n, "headerFormat"), text(n, "headerText"), text(n, "headerMediaUrl"),
                text(n, "bodyText"), examples, text(n, "footerText"), text(n, "status"), text(n, "metaTemplateId"),
                text(n, "rejectedReason"), text(n, "submitError"), instant(n, "createdAt"),
                instantOrNull(n, "updatedAt"));
    }

    static Campaign campaign(JsonNode n) {
        JsonNode criteria = n.get("targetingCriteria");
        JsonNode whatsapp = n.get("whatsapp");
        return new Campaign(uuid(n, "id"), uuidOrNull(n, "ownerId"), text(n, "name"), text(n, "description"),
                text(n, "status"), canonicalOrNull(criteria), canonicalOrNull(whatsapp), targeting(criteria),
                content(whatsapp), count(n, "targetedCount"), count(n, "sentCount"), count(n, "deliveredCount"),
                count(n, "readCount"), count(n, "failedCount"), instantOrNull(n, "scheduledAt"),
                instantOrNull(n, "sentAt"), uuidOrNull(n, "createdById"), instant(n, "createdAt"),
                instantOrNull(n, "updatedAt"));
    }

    static CampaignMessage campaignMessage(JsonNode n) {
        return new CampaignMessage(uuid(n, "id"), uuidOrNull(n, "customerCampaignId"), uuidOrNull(n, "customerId"),
                text(n, "phone"), text(n, "templateName"), text(n, "waMessageId"), text(n, "status"),
                text(n, "error"), instant(n, "createdAt"), instantOrNull(n, "updatedAt"));
    }

    static ChatMessage chatMessage(JsonNode n) {
        return new ChatMessage(uuid(n, "id"), uuidOrNull(n, "ownerId"), text(n, "contactPhone"),
                text(n, "contactName"), text(n, "direction"), text(n, "type"), text(n, "body"),
                text(n, "waMessageId"), text(n, "status"), instantOrNull(n, "readAt"), uuidOrNull(n, "sentById"),
                instant(n, "createdAt"));
    }

    static AiConversation aiConversation(JsonNode n) {
        return new AiConversation(uuid(n, "id"), text(n, "userId"), text(n, "title"), count(n, "messageCount"),
                canonicalOrNull(n.get("messages")), canonicalOrNull(n.get("aiHistory")), instant(n, "createdAt"),
                instantOrNull(n, "updatedAt"));
    }

    static Notification notification(JsonNode n) {
        return new Notification(uuid(n, "id"), text(n, "userId"), text(n, "type"), text(n, "channel"),
                text(n, "title"), text(n, "message"), bool(n, "isRead", false), canonicalOrNull(n.get("metadata")),
                instant(n, "createdAt"), instantOrNull(n, "updatedAt"));
    }

    static WebPushSubscription webPushSubscription(JsonNode n) {
        return new WebPushSubscription(uuid(n, "id"), text(n, "userId"), text(n, "endpoint"), text(n, "p256dh"),
                text(n, "auth"), text(n, "userAgent"), instant(n, "createdAt"), instantOrNull(n, "updatedAt"));
    }

    // ── JSON columns ─────────────────────────────────────────────────────────

    /** Bref {@code targetingCriteria}: shops and the purchase criteria (whole numbers only). */
    static Targeting targeting(JsonNode c) {
        if (c == null || !c.isObject()) {
            return Targeting.NONE;
        }
        boolean[] unreadable = {false};
        List<UUID> shops = new ArrayList<>();
        JsonNode ids = c.get("shopIds");
        if (ids != null && ids.isArray()) {
            for (JsonNode id : ids) {
                try {
                    shops.add(UUID.fromString(id.asText()));
                } catch (IllegalArgumentException e) {
                    unreadable[0] = true;
                }
            }
        }
        return new Targeting(shops, whole(c, "inactiveForDays", unreadable), whole(c, "purchasedWithinDays", unreadable),
                whole(c, "totalSpentMin", unreadable), whole(c, "totalSpentMax", unreadable),
                whole(c, "totalInvoicesMin", unreadable), whole(c, "totalInvoicesMax", unreadable), unreadable[0]);
    }

    /** Bref {@code whatsapp}: the template, the header media and the body variables. */
    static Content content(JsonNode w) {
        if (w == null || !w.isObject()) {
            return null;
        }
        List<Variable> variables = new ArrayList<>();
        JsonNode vars = w.get("bodyVariables");
        if (vars != null && vars.isArray()) {
            for (JsonNode v : vars) {
                variables.add(new Variable(text(v, "paramName"), text(v, "source"), text(v, "value")));
            }
        }
        return new Content(text(w, "templateName"), text(w, "language"), text(w, "headerFormat"),
                text(w, "headerMediaUrl"), text(w, "bodyText"), variables);
    }

    private static Long whole(JsonNode c, String field, boolean[] unreadable) {
        JsonNode v = c.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            BigDecimal d = v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().strip());
            return d.stripTrailingZeros().scale() <= 0 ? d.longValueExact() : unreadable(unreadable);
        } catch (ArithmeticException | NumberFormatException e) {
            return unreadable(unreadable);
        }
    }

    private static Long unreadable(boolean[] flag) {
        flag[0] = true;
        return null;
    }

    /** Canonical JSON text: object keys sorted recursively, compact; {@code null} for an absent / null value. */
    static String canonicalOrNull(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sorted(v));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("JSON column unreadable");
        }
    }

    private static JsonNode sorted(JsonNode v) {
        if (v.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            Iterator<String> names = v.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                fields.put(name, sorted(v.get(name)));
            }
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            fields.forEach(o::set);
            return o;
        }
        if (v.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            v.forEach(x -> a.add(sorted(x)));
            return a;
        }
        if (v.isNumber() && !v.isIntegralNumber()) {
            // jsonb and the export may write the same number differently (1.50 / 1.5 / 1.0 and 1)
            return JsonNodeFactory.instance.numberNode(v.decimalValue().stripTrailingZeros());
        }
        return v;
    }

    private static int count(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return 0;
        }
        return BrefRows.integer(n, field);
    }

    private static UUID uuidOrNull(JsonNode n, String field) {
        String v = text(n, field);
        return v == null || v.isBlank() ? null : UUID.fromString(v.strip());
    }
}
