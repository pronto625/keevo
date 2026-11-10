package com.keevo.whatsappcloud.webhook.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.whatsappcloud.inbox.domain.model.StatusUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WhatsAppWebhookService — parsing of Meta's payload, status ranking (21.5)")
class WhatsAppWebhookParsingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    @DisplayName("messages (text, button, image caption, location), contacts, statuses, template reviews")
    void parse() throws Exception {
        String payload = """
                {"object":"whatsapp_business_account","entry":[
                 {"id":"111","changes":[{"field":"messages","value":{
                   "metadata":{"phone_number_id":"555"},
                   "contacts":[{"profile":{"name":"Awa"},"wa_id":"237690112233"}],
                   "messages":[
                     {"from":"237690112233","id":"m1","timestamp":"1762770000","type":"text","text":{"body":"Bonjour"}},
                     {"from":"237690112233","id":"m2","timestamp":"1762770001","type":"button","button":{"text":"STOP"}},
                     {"from":"+237 677 88 99 00","id":"m3","timestamp":"bad","type":"image","image":{"caption":"photo"}},
                     {"from":"237677889900","id":"m4","timestamp":"9999999999","type":"location","location":{}},
                     {"from":"12","id":"m5","type":"text","text":{"body":"too short"}}],
                   "statuses":[{"id":"o1","status":"delivered"},{"id":"o2","status":"deleted"}]}}]},
                 {"id":"222","changes":[{"field":"message_template_status_update","value":{
                   "event":"REJECTED","message_template_id":42,"message_template_name":"promo","message_template_language":"fr",
                   "reason":"INVALID_FORMAT"}},{"field":"message_template_status_update","value":{"event":"PENDING_DELETION"}}]}]}
                """;
        WhatsAppWebhookService.Parsed p = WhatsAppWebhookService.parse(JSON.readTree(payload), NOW);
        assertThat(p.messages()).extracting(WhatsAppWebhookService.Inbound::id).containsExactly("m1", "m2", "m3", "m4");
        assertThat(p.messages().get(0).contactName()).isEqualTo("Awa");
        assertThat(p.messages().get(0).at()).isEqualTo(Instant.ofEpochSecond(1762770000));
        assertThat(p.messages().get(1).type()).isEqualTo("text");
        assertThat(p.messages().get(1).body()).isEqualTo("STOP");
        assertThat(p.messages().get(2).from()).isEqualTo("237677889900");
        assertThat(p.messages().get(2).body()).isEqualTo("photo");
        assertThat(p.messages().get(2).at()).isEqualTo(NOW);
        assertThat(p.messages().get(3).at()).isEqualTo(NOW); // never in the future
        assertThat(p.messages().get(3).body()).isNull();
        assertThat(p.statuses()).extracting(WhatsAppWebhookService.Status::status).containsExactly("DELIVERED");
        assertThat(p.reviews()).hasSize(1);
        assertThat(p.reviews().get(0).wabaId()).isEqualTo("222");
        assertThat(p.reviews().get(0).review().status()).isEqualTo("REJECTED");
        assertThat(p.reviews().get(0).review().metaTemplateId()).isEqualTo("42");
        assertThat(p.reviews().get(0).review().rejectedReason()).isEqualTo("INVALID_FORMAT");
    }

    @Test
    @DisplayName("not a WhatsApp Business delivery: ignored")
    void otherObject() throws Exception {
        assertThat(WhatsAppWebhookService.parse(JSON.readTree("{\"object\":\"page\"}"), NOW)).isNull();
    }

    @Test
    @DisplayName("statuses collapse to the one that supersedes (read > delivered > sent; failed after sent)")
    void collapse() {
        List<StatusUpdate> u = WhatsAppWebhookService.collapse(List.of(
                new WhatsAppWebhookService.Status("n", "a", "READ"),
                new WhatsAppWebhookService.Status("n", "a", "DELIVERED"),
                new WhatsAppWebhookService.Status("n", "b", "SENT"),
                new WhatsAppWebhookService.Status("n", "b", "FAILED")));
        assertThat(u).containsExactly(new StatusUpdate("a", "READ"), new StatusUpdate("b", "FAILED"));
        assertThat(StatusUpdate.supersedes("SENT", "DELIVERED")).isFalse();
        assertThat(StatusUpdate.supersedes("DELIVERED", null)).isTrue();
    }

    @Test
    @DisplayName("French labels of messages without text")
    void labels() {
        assertThat(WhatsAppWebhookService.label("image")).isEqualTo("Photo");
        assertThat(WhatsAppWebhookService.label("audio")).isEqualTo("Message vocal");
        assertThat(WhatsAppWebhookService.label("reaction")).isEqualTo("Message");
    }
}
