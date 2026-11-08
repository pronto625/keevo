package com.keevo.crm.api;

import java.util.UUID;

/**
 * Story 20.13 — a client to create through {@link ClientCommandPort#create}: {@code storeId} null = the default store
 * of the account (crm rule), {@code profile} null = no Bref fields.
 */
public record CreateClientInput(String name, String phone, String email, String notes, ClientProfileInput profile,
                                UUID storeId, UUID actorId) {
}
