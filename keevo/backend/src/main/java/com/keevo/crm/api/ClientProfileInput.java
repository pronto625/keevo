package com.keevo.crm.api;

/**
 * Story 20.2 — the Bref fields of a client as sent by a client app (REST or sync), raw: on an update each value
 * {@code null} (absent) leaves the stored value unchanged and a blank string clears it; on a creation {@code null} and
 * blank both mean « none ». Validated by the crm (one rule set for REST and sync) → {@code VALIDATION_ERROR} with a
 * French message.
 *
 * @param clientType  {@code PERSON} | {@code COMPANY} (any case)
 * @param niu         ≤ 50 characters once trimmed
 * @param rccm        ≤ 50
 * @param address     ≤ 255
 * @param contactName ≤ 120
 * @param website     ≤ 200
 * @param birthDate   ISO date {@code YYYY-MM-DD}, not in the future
 */
public record ClientProfileInput(String clientType, String niu, String rccm, String address, String contactName,
                                 String website, String birthDate) {

    /** Nothing given — what a V1 client sends. */
    public static final ClientProfileInput NONE = new ClientProfileInput(null, null, null, null, null, null, null);
}
