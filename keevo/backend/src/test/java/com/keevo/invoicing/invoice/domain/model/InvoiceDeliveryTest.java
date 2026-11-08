package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.event.catalog.invoicing.InvoiceSentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("Sending channel — legacy rows read as WhatsApp, request backoff (Story 20.8)")
class InvoiceDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");

    @Test
    @DisplayName("a sending recorded before 20.8 (no channel) reads WhatsApp; never sent → no channel")
    void legacyRowsReadWhatsApp() {
        assertThat(new InvoiceDelivery(NOW, "+237677000111", 2).channel()).isEqualTo(SendChannel.WHATSAPP);
        assertThat(new InvoiceDelivery(null, null, 1).channel()).isEqualTo(SendChannel.WHATSAPP);
        assertThat(InvoiceDelivery.NONE.channel()).isNull();
        InvoiceDelivery sms = InvoiceDelivery.NONE.sentTo("+237677000111", SendChannel.SMS, NOW);
        assertThat(sms.channel()).isEqualTo(SendChannel.SMS);
        assertThat(sms.sendCount()).isEqualTo(1);
        assertThat(sms.lastSendStatus()).isEqualTo(SendStatus.SENT);
    }

    @Test
    @DisplayName("an InvoiceSentEvent stored before 20.8 (channel null) is audited as WhatsApp")
    void legacyEventReadsWhatsApp() {
        InvoiceSentEvent stored = new InvoiceSentEvent(UUID.randomUUID(), "FAC-2026-000001", "Awa", 1000, "+237 6",
                null, "kv_test01", NOW, false, null);
        assertThat(stored.channelOrDefault()).isEqualTo("WHATSAPP");
        assertThat(new InvoiceSentEvent(UUID.randomUUID(), "FAC-2026-000001", "Awa", 1000, "+237 6", null,
                "kv_test01", NOW).channel()).isEqualTo("WHATSAPP");
    }

    @Test
    @DisplayName("SendChannel.parse: blank = WhatsApp, case-insensitive, unknown refused")
    void parse() {
        assertThat(SendChannel.parse(null)).isEqualTo(SendChannel.WHATSAPP);
        assertThat(SendChannel.parse("  ")).isEqualTo(SendChannel.WHATSAPP);
        assertThat(SendChannel.parse(" email ")).isEqualTo(SendChannel.EMAIL);
        assertThat(catchThrowableOfType(IllegalArgumentException.class, () -> SendChannel.parse("fax"))).isNotNull();
    }

    @Test
    @DisplayName("backoff 30 s, 1, 2, 4 min (Bref polls every 30 s, 5 attempts)")
    void backoff() {
        assertThat(InvoiceSendRequest.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(InvoiceSendRequest.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(InvoiceSendRequest.backoff(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(InvoiceSendRequest.MAX_ATTEMPTS).isEqualTo(5);
    }
}
