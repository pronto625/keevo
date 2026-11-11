package com.keevo.crm.api;

import java.util.UUID;

/**
 * Story 21.6 — a client who may receive a marketing campaign (consent given by the team, no STOP, not archived), as
 * the campaigns read it: one per phone number ({@code phoneE164}, {@code +} and digits), {@code storeId} {@code null}
 * for a legacy client of every store.
 */
public record MarketingRecipient(UUID clientId, String name, String phoneE164, UUID storeId) {
}
