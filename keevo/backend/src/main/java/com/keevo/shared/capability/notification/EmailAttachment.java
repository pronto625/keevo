package com.keevo.shared.capability.notification;

/**
 * Story 19.9 — a file attached to an {@link EmailMessage}.
 *
 * @param filename    name shown to the recipient
 * @param contentType media type ({@code application/pdf}); {@code application/octet-stream} when null
 * @param content     raw bytes (base64-encoded by the provider adapter)
 */
public record EmailAttachment(String filename, String contentType, byte[] content) {
}
