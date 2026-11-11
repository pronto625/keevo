package com.keevo.identity.employee.domain.port.out;

import com.keevo.identity.employee.domain.model.MembershipInvitation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.6b — driven port of {@code public.membership_invitations} (one row per company and person).
 */
public interface MembershipInvitationRepository {

    /**
     * Inserts the invitation, or renews the row of the same (company, person) with what was typed (PENDING again, new
     * expiry) — unless it was DECLINED at or after {@code declinedBefore} by a person who already refused
     * {@link MembershipInvitation#DECLINES_BEFORE_COOLDOWN} times (left untouched). One conditional upsert.
     *
     * @param sentBefore an invitation message is due only when none was sent to the pair since this instant
     * @return true when an invitation message is due (row inserted, or renewed with no message since
     *         {@code sentBefore}), false otherwise
     */
    boolean upsertPending(MembershipInvitation invitation, Instant now, Instant declinedBefore, Instant sentBefore);

    /** Invitations of the company created or renewed at or after {@code since} (the rolling daily cap). */
    int countInvitedSince(UUID tenantId, Instant since);

    /**
     * A company's live pending invitations (PENDING, not expired at {@code now}); with {@code storeId}, a MANAGER's
     * view: that store's EMPLOYEE / VIEWER invitations only (19.5 team rules).
     */
    List<MembershipInvitation> findPendingForTenant(UUID tenantId, UUID storeId, Instant now);

    /** The person's live pending invitations from ACTIVE companies, newest first (at most 50), with the company. */
    List<PendingForUser> findPendingForUser(UUID userId, Instant now);

    /**
     * The person's live pending invitation {@code id} from an ACTIVE company, row-locked ({@code FOR UPDATE}) until
     * the end of the transaction; empty for anything else (someone else's, expired, answered, cancelled).
     */
    Optional<PendingForUser> lockPendingForUser(UUID id, UUID userId, Instant now);

    /** The tenant schema of the person's live pending invitation {@code id} from an ACTIVE company (no lock). */
    Optional<String> pendingTenantSchema(UUID id, UUID userId, Instant now);

    /** Whether the person joined the company through an invitation (an ACCEPTED row; unique-key lookup). */
    boolean hasAccepted(UUID tenantId, UUID userId);

    /**
     * Sets a PENDING row to {@code status} (ACCEPTED, DECLINED or CANCELLED) answered at {@code now}; a refusal is
     * counted, an acceptance resets the count. False when not PENDING.
     */
    boolean markResponded(UUID id, MembershipInvitation.Status status, Instant now);

    /**
     * Cancels the company's live pending invitation {@code id} (with {@code storeId}: the MANAGER's view, as in
     * {@link #findPendingForTenant}).
     *
     * @return false when there is no such invitation
     */
    boolean cancel(UUID id, UUID tenantId, UUID storeId, Instant now);

    /**
     * The identifier kind of the accepted invitations of these users in the company — only members who joined by
     * invitation appear. One query; an empty collection answers an empty map without a query.
     */
    Map<UUID, MembershipInvitation.IdentifierKind> acceptedIdentifierKinds(UUID tenantId, Collection<UUID> userIds);

    /** A pending invitation with the inviting company. */
    record PendingForUser(MembershipInvitation invitation, String tenantCode, String tenantName,
                          String tenantSchema) {
    }
}
