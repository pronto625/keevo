package com.keevo.whatsappcloud.campaign.domain.model;

import java.util.UUID;

/**
 * Story 21.6 — one recipient of a campaign, frozen when the send starts: the phone (digits, Meta's format), the client
 * and the names used by the template variables; its {@link RecipientStatus}, Meta's message id once sent, a French
 * reason when not sent; {@code deliveryStatus} = Meta's status of the sent message (read side only).
 */
public record Recipient(String phone, UUID clientId, String clientName, String storeName, RecipientStatus status,
                        String waMessageId, String error, String deliveryStatus) {

    public static Recipient pending(String phone, UUID clientId, String clientName, String storeName) {
        return new Recipient(phone, clientId, clientName, storeName, RecipientStatus.PENDING, null, null, null);
    }
}
