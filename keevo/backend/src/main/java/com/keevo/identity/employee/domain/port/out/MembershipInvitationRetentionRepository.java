package com.keevo.identity.employee.domain.port.out;

import java.time.Instant;

/**
 * Story 19.11c (routed from review 19-6b) — retention of {@code public.membership_invitations}: the rows that will
 * never become a membership keep what the owner typed (phone or username, names) of people who refused or never
 * answered; they are deleted once old enough.
 */
public interface MembershipInvitationRetentionRepository {

    /**
     * Deletes, in one statement, the DECLINED and CANCELLED invitations answered before {@code cutoff} and the PENDING
     * invitations that expired before {@code cutoff}. ACCEPTED rows are never deleted (the company still shows the
     * kind of identifier its member joined with). Returns the number of rows deleted.
     */
    int purgeNeverMembershipsBefore(Instant cutoff);
}
