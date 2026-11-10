package com.keevo.whatsappcloud.account.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.5 — the company's connected WhatsApp Business number ({@code public.whatsapp_accounts}): its WABA, its
 * number id (webhook routing key), what Meta shows of it, and its access token, encrypted ({@code tokenEnc}, opened
 * only in memory for a Graph call — {@link #toString()} never prints it).
 */
public record WhatsAppAccount(UUID tenantId, String tenantSchema, String wabaId, String phoneNumberId,
                              String displayPhoneNumber, String verifiedName, byte[] tokenEnc, UUID connectedBy,
                              Instant connectedAt) {

    @Override
    public String toString() {
        return "WhatsAppAccount[tenant=" + tenantSchema + ", waba=" + wabaId + ", phoneNumberId=" + phoneNumberId + "]";
    }
}
