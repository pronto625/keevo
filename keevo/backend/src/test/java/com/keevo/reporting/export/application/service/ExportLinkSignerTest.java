package com.keevo.reporting.export.application.service;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExportLinkSigner (v1s-18-1)")
class ExportLinkSignerTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghij";
    private final ExportLinkSigner signer = new ExportLinkSigner(SECRET);
    private final UUID job = UUID.randomUUID();
    private final Instant exp = Instant.parse("2026-10-13T10:00:00Z");

    @Test
    @DisplayName("sign → verify round-trips tenant, job and expiry (seconds)")
    void roundTrip() {
        String token = signer.sign("kv_abc123", job, exp.plusMillis(700));
        assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        ExportLinkSigner.Claims c = signer.verify(token);
        assertThat(c.tenantSchema()).isEqualTo("kv_abc123");
        assertThat(c.jobId()).isEqualTo(job);
        assertThat(c.expiresAt()).isEqualTo(exp);
    }

    @Test
    @DisplayName("tampered payload (other tenant), tampered signature, other secret, garbage → EXPORT_LINK_INVALID")
    void tamperedRejected() {
        String token = signer.sign("kv_abc123", job, exp);
        String sig = token.substring(token.indexOf('.') + 1);
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("kv_zzz999|" + job + "|" + exp.getEpochSecond()).getBytes(StandardCharsets.UTF_8));

        assertInvalid(forgedPayload + "." + sig);
        assertInvalid(token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA"));
        assertInvalid(new ExportLinkSigner("another-secret-of-at-least-32-chars!").sign("kv_abc123", job, exp));
        assertInvalid("not-a-token");
        assertInvalid("a.b.c");
        assertInvalid("");
        assertInvalid(null);
        assertInvalid("x".repeat(600) + ".y");
    }

    @Test
    @DisplayName("a signed payload with an invalid schema is still rejected")
    void invalidSchemaRejected() {
        assertThatThrownBy(() -> signer.sign("public", job, exp)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank secret → random per instance: tokens of one instance fail on another")
    void blankSecretIsRandom() {
        ExportLinkSigner a = new ExportLinkSigner("");
        ExportLinkSigner b = new ExportLinkSigner((String) null);
        String token = a.sign("kv_abc123", job, exp);
        assertThat(a.verify(token).jobId()).isEqualTo(job);
        assertThatThrownBy(() -> b.verify(token)).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("a configured secret shorter than 32 characters fails the start")
    void shortSecretRejected() {
        assertThatThrownBy(() -> new ExportLinkSigner("abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
        assertThatThrownBy(() -> new ExportLinkSigner("x".repeat(31))).isInstanceOf(IllegalStateException.class);
        new ExportLinkSigner("x".repeat(32));
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> signer.verify(token))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo("EXPORT_LINK_INVALID");
    }
}
