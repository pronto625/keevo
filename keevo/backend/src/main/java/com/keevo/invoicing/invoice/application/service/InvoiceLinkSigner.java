package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Signed public links to an invoice PDF (Story 14.2), sent to the client by WhatsApp — same scheme as the export
 * links: token = {@code base64url(tenantSchema|invoiceId|expEpochSec) + "." + base64url(HMAC-SHA256)}, MAC over the
 * encoded payload, compared in constant time; valid {@link #VALIDITY} (30 days).
 *
 * <p>Secret: {@code keevo.invoicing.link-secret}, defaulting to {@code keevo.export.link-secret}; at least 32
 * characters (shorter fails the start); both blank ⇒ random per boot (links die on restart) + WARN — a real secret
 * is a go-live requirement.
 *
 * <p>Story 20.7 — the payload gains the invoice's link version ({@code tenantSchema|invoiceId|expEpochSec|version});
 * a token of the 14.2 shape (3 parts) reads as version 0, so the links already sent keep opening until the first
 * rotation. The lifetime is chosen by the caller (tenant preference: 30 days by default, 90 for an imported tenant).
 */
@Component
public class InvoiceLinkSigner {

    /** How long a sent link opens the invoice when the tenant chose nothing (Story 14.2). */
    public static final Duration VALIDITY = Duration.ofDays(30);
    static final int MIN_SECRET_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(InvoiceLinkSigner.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAX_TOKEN_LENGTH = 512;

    private final byte[] secret;

    @Autowired
    public InvoiceLinkSigner(@Value("${keevo.invoicing.link-secret:}") String invoiceSecret,
                             @Value("${keevo.export.link-secret:}") String exportSecret) {
        this(invoiceSecret != null && !invoiceSecret.isBlank() ? invoiceSecret : exportSecret);
    }

    public InvoiceLinkSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("INVOICE_LINK_SECRET_MISSING — keevo.invoicing.link-secret and keevo.export.link-secret are "
                    + "blank: using a random per-boot secret, invoice links sent by WhatsApp will not survive a "
                    + "restart (set KEEVO_INVOICE_LINK_SECRET or KEEVO_EXPORT_LINK_SECRET)");
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("keevo.invoicing.link-secret must be at least " + MIN_SECRET_LENGTH
                    + " characters long (got " + secret.length() + ")");
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Verified token content (expiry and version not checked: the caller answers 410 / 404). {@code version} = 0 for a
     * token of the 14.2 shape.
     */
    public record Claims(String tenantSchema, UUID invoiceId, Instant expiresAt, int version) {

        public Claims(String tenantSchema, UUID invoiceId, Instant expiresAt) {
            this(tenantSchema, invoiceId, expiresAt, 0);
        }
    }

    /** A token of link version 0 (the 14.2 shape). */
    public String sign(String tenantSchema, UUID invoiceId, Instant expiresAt) {
        String raw = TenantSchema.validate(tenantSchema) + "|" + invoiceId + "|" + expiresAt.getEpochSecond();
        String payload = b64(raw.getBytes(StandardCharsets.UTF_8));
        return payload + "." + b64(mac(payload));
    }

    /** Story 20.7 — a token bound to the invoice's current link version (version 0 → the 14.2 shape). */
    public String sign(String tenantSchema, UUID invoiceId, Instant expiresAt, int version) {
        if (version <= 0) {
            return sign(tenantSchema, invoiceId, expiresAt);
        }
        String raw = TenantSchema.validate(tenantSchema) + "|" + invoiceId + "|" + expiresAt.getEpochSecond() + "|"
                + version;
        String payload = b64(raw.getBytes(StandardCharsets.UTF_8));
        return payload + "." + b64(mac(payload));
    }

    /** @throws DomainException {@code INVOICE_LINK_INVALID} on any malformed or tampered token */
    public Claims verify(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) throw invalid();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) throw invalid();
        String payload = token.substring(0, dot);
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(mac(payload), given)) throw invalid();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if ((parts.length != 3 && parts.length != 4) || !TenantSchema.isValid(parts[0])) throw invalid();
            int version = parts.length == 4 ? Integer.parseInt(parts[3]) : 0;
            if (version < 0) throw invalid();
            return new Claims(parts[0], UUID.fromString(parts[1]), Instant.ofEpochSecond(Long.parseLong(parts[2])),
                    version);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static DomainException invalid() {
        return new DomainException(ErrorCode.INVOICE_LINK_INVALID, "Invalid invoice link");
    }
}
