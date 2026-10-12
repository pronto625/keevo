package com.keevo.reporting.export.application.service;

import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Signed export download links (Story v1s-18-1).
 *
 * <p>Token = {@code base64url(tenantSchema|jobId|expEpochSec) + "." + base64url(HMAC-SHA256)}, the
 * MAC computed over the encoded payload and compared in constant time. The token binds tenant,
 * job and expiry, so it cannot open another tenant's job nor outlive the file. Secret:
 * {@code keevo.export.link-secret}, at least 32 characters (shorter fails the start); blank ⇒
 * random per boot (links die on restart) + WARN.
 */
@Component
public class ExportLinkSigner {

    private static final Logger log = LoggerFactory.getLogger(ExportLinkSigner.class);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAX_TOKEN_LENGTH = 512;
    static final int MIN_SECRET_LENGTH = 32;

    private final byte[] secret;

    @Autowired
    public ExportLinkSigner(ExportProperties properties) {
        this(properties.getLinkSecret());
    }

    public ExportLinkSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("EXPORT_LINK_SECRET_MISSING — keevo.export.link-secret is blank: using a random per-boot "
                    + "secret, signed export links will not survive a restart (set KEEVO_EXPORT_LINK_SECRET)");
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("keevo.export.link-secret (KEEVO_EXPORT_LINK_SECRET) must be at least "
                    + MIN_SECRET_LENGTH + " characters long (got " + secret.length() + ") — leave it blank only "
                    + "for a random per-boot secret");
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Verified token content. */
    public record Claims(String tenantSchema, UUID jobId, Instant expiresAt) {}

    public String sign(String tenantSchema, UUID jobId, Instant expiresAt) {
        String raw = TenantSchema.validate(tenantSchema) + "|" + jobId + "|" + expiresAt.getEpochSecond();
        String payload = b64(raw.getBytes(StandardCharsets.UTF_8));
        return payload + "." + b64(mac(payload));
    }

    /**
     * Verify the signature, then decode. Expiry is NOT checked here (the caller answers 410).
     *
     * @throws DomainException EXPORT_LINK_INVALID on any malformed or tampered token
     */
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
            if (parts.length != 3 || !TenantSchema.isValid(parts[0])) throw invalid();
            return new Claims(parts[0], UUID.fromString(parts[1]), Instant.ofEpochSecond(Long.parseLong(parts[2])));
        } catch (IllegalArgumentException e) { // also NumberFormatException
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

    private static DomainException invalid() {
        return new DomainException(ErrorCode.EXPORT_LINK_INVALID, "Invalid export download link");
    }
}
