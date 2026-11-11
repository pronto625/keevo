package com.keevo.clientportal.invoice.domain.model;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Story 21.7 — keyset position in a client's invoice list: {@code (issuedAt, tenantId, invoiceId)}, the order of
 * {@code idx_invoice_phone_index_phone}. Handed to the app as an opaque base64url token of 40 bytes (epoch micros,
 * tenant id, invoice id); the app passes it back as is and never shows it. A token that does not decode is refused
 * ({@link IllegalArgumentException}; the controller answers 400).
 */
public record PortalCursor(Instant issuedAt, UUID tenantId, UUID invoiceId) {

    static final int BYTES = 8 + 16 + 16;
    /** Issue instants the database can hold for an invoice: 2000-01-01 to 9999-12-31 (epoch microseconds). */
    static final long MIN_MICROS = 946_684_800_000_000L;
    static final long MAX_MICROS = 253_402_300_799_000_000L;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** The opaque token of this position. */
    public String encode() {
        long micros = Math.addExact(Math.multiplyExact(issuedAt.getEpochSecond(), 1_000_000L),
                issuedAt.getNano() / 1_000L);
        ByteBuffer buffer = ByteBuffer.allocate(BYTES)
                .putLong(micros)
                .putLong(tenantId.getMostSignificantBits()).putLong(tenantId.getLeastSignificantBits())
                .putLong(invoiceId.getMostSignificantBits()).putLong(invoiceId.getLeastSignificantBits());
        return ENCODER.encodeToString(buffer.array());
    }

    /**
     * @param token a token from {@link #encode}, null or blank = no position (first page)
     * @throws IllegalArgumentException when the token is not one
     */
    public static PortalCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = token.length() > 64 ? null : DECODER.decode(token.strip());
        } catch (IllegalArgumentException e) {
            bytes = null;
        }
        if (bytes == null || bytes.length != BYTES) {
            throw new IllegalArgumentException("invalid portal cursor");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long micros = buffer.getLong();
        if (micros < MIN_MICROS || micros > MAX_MICROS) { // a forged position, never one this API handed out
            throw new IllegalArgumentException("invalid portal cursor");
        }
        Instant issuedAt = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L);
        return new PortalCursor(issuedAt, new UUID(buffer.getLong(), buffer.getLong()),
                new UUID(buffer.getLong(), buffer.getLong()));
    }
}
