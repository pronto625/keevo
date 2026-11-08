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
 * @param storeId Story 20.14 — the store of the document ({@code null}: none); an owner's derived client goes there
 *                when it is still active (a store member's always goes to its own store)
 */
public record DocumentClientInput(String name, String phone, String email, String rccm, String niu, String address,
                                  java.util.UUID storeId) {

    /** Story 20.6 shape: a document without store. */
    public DocumentClientInput(String name, String phone, String email, String rccm, String niu, String address) {
        this(name, phone, email, rccm, niu, address, null);
    }
}
