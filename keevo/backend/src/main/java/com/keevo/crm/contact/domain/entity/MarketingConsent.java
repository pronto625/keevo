package com.keevo.crm.contact.domain.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.2 — marketing consent of a client, Bref semantics: the team records the client's consent
 * ({@code consentAt} / {@code consentBy}, cleared on withdrawal); the client's own STOP ({@code optOutAt}, 21.5) is
 * never written nor cleared by the team and always wins.
 *
 * @param consentAt when the team recorded the consent, {@code null} = none / withdrawn
 * @param consentBy who recorded it (user id), {@code null} with {@code consentAt}
 * @param optOutAt  when the client opted out (STOP), {@code null} = never
 */
public record MarketingConsent(Instant consentAt, UUID consentBy, Instant optOutAt) {

    public static final MarketingConsent NONE = new MarketingConsent(null, null, null);

    /** Consent given now by {@code actorId}; the opt-out is kept. */
    public MarketingConsent given(Instant now, UUID actorId) {
        return new MarketingConsent(now, actorId, optOutAt);
    }

    /** Consent withdrawn: date and author cleared; the opt-out is kept. */
    public MarketingConsent withdrawn() {
        return new MarketingConsent(null, null, optOutAt);
    }

    public boolean isGiven() {
        return consentAt != null;
    }

    /** Consent given and no STOP (archival is checked by {@link Client#marketingEligible()}). */
    public boolean allowsMarketing() {
        return consentAt != null && optOutAt == null;
    }
}
