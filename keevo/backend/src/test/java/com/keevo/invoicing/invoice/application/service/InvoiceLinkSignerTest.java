package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InvoiceLinkSigner (Story 14.2)")
class InvoiceLinkSignerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final InvoiceLinkSigner signer = new InvoiceLinkSigner(SECRET);

    @Test
    @DisplayName("sign → verify returns schema, invoice and expiry")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        Instant exp = Instant.parse("2026-11-29T10:00:00Z");
        InvoiceLinkSigner.Claims claims = signer.verify(signer.sign("kv_abc123", id, exp));
        assertThat(claims.tenantSchema()).isEqualTo("kv_abc123");
        assertThat(claims.invoiceId()).isEqualTo(id);
        assertThat(claims.expiresAt()).isEqualTo(exp);
    }

    @Test
    @DisplayName("tampered payload, foreign secret, garbage → INVOICE_LINK_INVALID")
    void tampered() {
        String token = signer.sign("kv_abc123", UUID.randomUUID(), Instant.parse("2026-11-29T10:00:00Z"));
        String otherPayload = signer.sign("kv_zzz999", UUID.randomUUID(), Instant.parse("2027-11-29T10:00:00Z"))
                .split("\\.")[0];
        String forged = otherPayload + "." + token.split("\\.")[1];
        for (String bad : new String[]{forged, token + "x", "abc", "", "a.b.c",
                new InvoiceLinkSigner("ffffffffffffffffffffffffffffffff").sign("kv_abc123", UUID.randomUUID(),
                        Instant.parse("2026-11-29T10:00:00Z"))}) {
            assertThatThrownBy(() -> signer.verify(bad)).as(bad).isInstanceOf(DomainException.class)
                    .satisfies(e -> assertThat(((DomainException) e).getDomainCode())
                            .isEqualTo("INVOICE_LINK_INVALID"));
        }
    }

    @Test
    @DisplayName("secret: shorter than 32 characters fails the start; invoicing secret blank → export secret")
    void secret() {
        assertThatThrownBy(() -> new InvoiceLinkSigner("short")).isInstanceOf(IllegalStateException.class);
        InvoiceLinkSigner fromExport = new InvoiceLinkSigner("", SECRET);
        String token = fromExport.sign("kv_abc123", UUID.randomUUID(), Instant.parse("2026-11-29T10:00:00Z"));
        assertThat(signer.verify(token).tenantSchema()).isEqualTo("kv_abc123");
        // both blank → random per boot, still usable within the boot
        InvoiceLinkSigner random = new InvoiceLinkSigner("", "");
        UUID id = UUID.randomUUID();
        assertThat(random.verify(random.sign("kv_abc123", id, Instant.now())).invoiceId()).isEqualTo(id);
    }
}
