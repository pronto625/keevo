package com.keevo.identity.employee.domain.port.in;

import com.keevo.identity.employee.domain.model.MembershipInvitation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 19.6b — invitations of existing accounts: the company side (list, cancel — current company from the tenant
 * context) and the person's side (list, accept, decline — whatever company the caller is in).
 */
public interface MembershipInvitationUseCase {

    /** The current company's live pending invitations, of one store when {@code storeScope} is not null. */
    List<MembershipInvitation> listForCompany(UUID storeScope);

    /**
     * Cancels one of the current company's live pending invitations (of {@code storeScope} when not null).
     *
     * @throws com.keevo.shared.domain.exception.DomainException INVITATION_NOT_FOUND otherwise
     */
    void cancel(UUID invitationId, UUID storeScope);

    /** The person's live pending invitations from active companies, newest first. */
    List<PendingInvitation> listForUser(UUID userId);

    /**
     * Accepts the person's invitation: membership + employee row in the inviting company.
     *
     * @throws com.keevo.shared.domain.exception.DomainException INVITATION_NOT_FOUND, ACCOUNT_RECOVERY_REQUIRED,
     *         PLAN_LIMIT_EXCEEDED (the invitation stays pending), MEMBER_ALREADY_IN_COMPANY
     */
    JoinedCompany accept(UUID invitationId, UUID userId);

    /** Declines the person's invitation; nobody is notified. @throws DomainException INVITATION_NOT_FOUND */
    void decline(UUID invitationId, UUID userId);

    /** What the person sees of an invitation: the company and the role, never the inviter's data. */
    record PendingInvitation(UUID id, String companyName, String role, Instant invitedAt, Instant expiresAt) {
    }

    /** The company joined, as the login step lists it (the app then selects it). */
    record JoinedCompany(String tenantCode, String tenantName, String role) {
    }
}
