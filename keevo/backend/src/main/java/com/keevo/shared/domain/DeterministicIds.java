package com.keevo.shared.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * DeterministicIds — name-based (RFC 4122 version 5, SHA-1) ids computed identically on the
 * backend and on the app (v1s-13-13).
 *
 * <p>Used for the stock movements written by an owner cancel/correct of a COMPLETED sale: the
 * device writes its local movement and the backend its own with the same id, so the pull's
 * {@code INSERT OR IGNORE} keeps a single history row. The app mirror is
 * {@code keevo/app/packages/keevo_core/lib/sync/stock_movement_ids.dart}; namespace and names must stay byte-identical.</p>
 *
 * <p>Pure Java — NO Spring/JPA imports.</p>
 */
public final class DeterministicIds {

    /** Keevo stock-movement namespace (shared with the app). */
    public static final UUID MOVEMENT_NAMESPACE = UUID.fromString("4b6f1d2e-8c3a-4e5f-9a7b-1c2d3e4f5a6b");

    private DeterministicIds() {}

    /** SALE_CANCELLED movement of a sale item: v5(ns, {@code "SALE_CANCELLED:<saleItemId>"}). */
    public static UUID saleCancelledMovementId(UUID saleItemId) {
        Objects.requireNonNull(saleItemId, "saleItemId must not be null");
        return uuidV5(MOVEMENT_NAMESPACE, "SALE_CANCELLED:" + saleItemId);
    }

    /**
     * ADJUSTMENT movement of a sale item for one correction request:
     * v5(ns, {@code "ADJUSTMENT:<correctionId>:<saleItemId>"}).
     */
    public static UUID saleCorrectionMovementId(UUID correctionId, UUID saleItemId) {
        Objects.requireNonNull(correctionId, "correctionId must not be null");
        Objects.requireNonNull(saleItemId, "saleItemId must not be null");
        return uuidV5(MOVEMENT_NAMESPACE, "ADJUSTMENT:" + correctionId + ":" + saleItemId);
    }

    /** RFC 4122 §4.3 name-based UUID, version 5 (SHA-1), IETF variant; name encoded in UTF-8. */
    public static UUID uuidV5(UUID namespace, String name) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(name, "name must not be null");
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
        sha1.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // IETF variant
        ByteBuffer bb = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
