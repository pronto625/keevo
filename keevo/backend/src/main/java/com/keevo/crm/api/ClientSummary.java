package com.keevo.crm.api;

import java.util.UUID;

/**
 * A client as seen by other modules through {@link ClientLookupPort} (Story 14.3): enough to pick a client and
 * prefill a document (e.g. an invoice), nothing more.
 *
 * @param id       the client id
 * @param name     the display name
 * @param phone    the phone number (E.164)
 * @param archived whether the client is archived
 */
public record ClientSummary(UUID id, String name, String phone, boolean archived) {
}
