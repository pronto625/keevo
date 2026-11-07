package com.keevo.crm.api;

/**
 * Story 20.6 — the client typed on an issued document (invoice), as handed to {@link ClientLookupPort#findOrCreateForDocument}
 * to find or create its CRM record (Bref « dérivation » : a sale records its customer). Every field is optional; the
 * CRM ignores what it cannot use (an unparseable phone means no derivation at all).
 *
 * @param name    the client name printed on the document
 * @param phone   the phone as typed (any form: {@code 699001122}, {@code +237 6 99 …}, {@code 00237…})
 * @param email   the client's email
 * @param rccm    trade register number
 * @param niu     tax number
 * @param address postal address
 */
public record DocumentClientInput(String name, String phone, String email, String rccm, String niu, String address) {
}
