package com.keevo.identity.api;

/**
 * Story 21.7 — one-time codes of the client portal (end clients of the merchants, not Keevo users), on the 19.2 code
 * machinery under their own purpose: a portal code never opens a merchant session and a merchant code never opens the
 * portal. Phones only (E.164 of an allowed country). No account is read or created here: the portal keeps its own
 * clients.
 */
public interface ClientPortalCodePort {

    /**
     * Sends a 6-digit code by SMS (and WhatsApp when a provider is configured). The answer never depends on whether
     * the phone has invoices.
     *
     * @param phone    phone as typed (a 9-digit Cameroonian number gets {@code +237})
     * @param clientIp caller address for the per-IP limit (null = not limited by IP)
     * @param language {@code Accept-Language} value (null = French)
     * @return validity of the code in seconds
     * @throws com.keevo.shared.domain.exception.DomainException {@code VALIDATION_FAILED} (not a phone),
     *         {@code PHONE_COUNTRY_NOT_SUPPORTED}, {@code OTP_TOO_MANY_REQUESTS}
     */
    int requestCode(String phone, String clientIp, String language);

    /**
     * Checks a portal code and consumes it. A wrong code's attempt is kept even though the call fails (own
     * transaction).
     *
     * @return the phone the code was sent to, normalized (E.164)
     * @throws com.keevo.shared.domain.exception.DomainException {@code OTP_INVALID}, {@code OTP_TOO_MANY_ATTEMPTS},
     *         {@code OTP_TOO_MANY_REQUESTS}, {@code VALIDATION_FAILED}, {@code PHONE_COUNTRY_NOT_SUPPORTED}
     */
    String verifyCode(String phone, String code, String clientIp);
}
