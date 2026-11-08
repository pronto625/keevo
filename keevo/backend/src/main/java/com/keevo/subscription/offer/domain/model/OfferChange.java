package com.keevo.subscription.offer.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.10 — one line of the catalogue change journal ({@code public.offer_change_log}).
 *
 * @param id        the line's id (keyset cursor only)
 * @param entity    what changed ({@link OfferEntity})
 * @param entityKey key of the changed row (code, plan:cycle, discount id) — never shown
 * @param summary   French summary, labels only
 * @param actorName French name of the author, {@code null} when unknown
 */
public record OfferChange(UUID id, OfferEntity entity, String entityKey, String summary, UUID actorId, String actorName,
                          Instant occurredAt) {
}
