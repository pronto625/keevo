package com.keevo.shared.infrastructure.security.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Story 21.5 (FUS-NFR4) — encryption at rest of the third-party secrets Keevo must use again later (a company's
 * WhatsApp Business access token): AES-256-GCM, a random 96-bit IV per value, a 128-bit tag, and associated data that
 * binds the value to its owner row ({@code context}, e.g. the company id) so a blob copied to another row never
 * decrypts.
 *
 * <p>Blob = {@code 0x01} (format version) ‖ IV (12 bytes) ‖ ciphertext ‖ tag. Key: {@code keevo.secrets.encryption-key}
 * (environment {@code KEEVO_SECRETS_ENCRYPTION_KEY}), base64 of exactly 32 random bytes, never in the database. No key →
 * {@link #isAvailable()} false (features needing it answer 503); a malformed key fails the startup. Neither the key nor
 * a clear value is ever logged.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    static final byte VERSION = 1;
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    static final String PROPERTY = "keevo.secrets.encryption-key";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${keevo.secrets.encryption-key:}") String base64Key) {
        this.key = parse(base64Key);
        if (key == null) {
            log.warn("[Secrets] {} not set: features storing third-party secrets are unavailable", PROPERTY);
        }
    }

    static SecretKey parse(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PROPERTY + " (KEEVO_SECRETS_ENCRYPTION_KEY) must be base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException(PROPERTY + " (KEEVO_SECRETS_ENCRYPTION_KEY) must decode to 32 bytes");
        }
        SecretKey k = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        return k;
    }

    /** True when a key is configured. */
    public boolean isAvailable() {
        return key != null;
    }

    /**
     * Encrypts {@code clear} bound to {@code context}.
     *
     * @throws IllegalStateException no key configured (check {@link #isAvailable()} first)
     */
    public byte[] encrypt(String clear, String context) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(context));
            byte[] sealed = cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length).put(VERSION).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Decrypts a blob of {@link #encrypt} with the same {@code context}.
     *
     * @throws IllegalStateException no key, unknown format, or the blob does not open with this key and context
     *                               (tampered, other row, rotated key) — the message never carries the value
     */
    public String decrypt(byte[] blob, String context) {
        requireKey();
        if (blob == null || blob.length < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != VERSION) {
            throw new IllegalStateException("Unknown secret format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES));
            cipher.updateAAD(aad(context));
            byte[] clear = cipher.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES);
            return new String(clear, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Secret does not open with this key and context");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret decryption failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(PROPERTY + " is not configured");
        }
    }

    private static byte[] aad(String context) {
        return ("keevo-secret:" + (context == null ? "" : context)).getBytes(StandardCharsets.UTF_8);
    }
}
