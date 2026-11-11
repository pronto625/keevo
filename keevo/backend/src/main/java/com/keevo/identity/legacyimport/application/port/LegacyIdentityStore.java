package com.keevo.identity.legacyimport.application.port;

import com.keevo.identity.api.LegacyIdentityImportPort.AccountMatch;
import com.keevo.identity.api.LegacyIdentityImportPort.AuthenticatorOutcome;
import com.keevo.identity.api.LegacyIdentityImportPort.ImportedMember;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyInvitation;
import com.keevo.identity.api.LegacyIdentityImportPort.SocialIdentity;
import com.keevo.identity.api.Role;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — persistence of the imported identities ({@code public.users}, {@code public.user_tenant_memberships},
 * {@code "<schema>".employees}, {@code "<schema>".tenant_preferences}, the suspension trace on {@code public.tenants};
 * 24.7: {@code public.membership_invitations}, {@code public.user_authenticators}). Every insert is
 * {@code ON CONFLICT DO NOTHING} and set-based (one statement per table).
 */
public interface LegacyIdentityStore {

    /** One query (UNION ALL of index lookups). */
    Map<UUID, List<AccountMatch>> findMatches(Collection<LegacyAccount> accounts);

    /** @return the ids inserted (absent ids only) */
    Set<UUID> insertAccounts(List<LegacyAccount> accounts, List<Role> roles, List<Boolean> active);

    /** @return account id → membership id, for the pairs inserted */
    Map<UUID, UUID> insertMemberships(UUID tenantId, List<LegacyAccount> accounts, List<Role> roles,
                                      List<Boolean> active);

    /** Employee id = the imported account id, user = {@link ImportedMember#userId()}. @return the ids inserted */
    Set<UUID> insertEmployees(String tenantSchema, List<ImportedMember> members);

    /** Story 24.7 — PENDING invitations, no message. @return Keevo account id → invitation id, for those inserted */
    Map<UUID, UUID> insertInvitations(UUID tenantId, UUID invitedBy, List<LegacyInvitation> invitations,
                                      Duration validity);

    /** Story 24.7 — @return one outcome per input, in input order */
    List<AuthenticatorOutcome> insertAuthenticators(List<SocialIdentity> identities);

    /** @return whether the row was created */
    boolean insertPreferencesIfAbsent(String tenantSchema, String sectorType);

    /** Sets the suspension date and reason of a suspended tenant when they are not set yet. */
    void traceSuspension(UUID tenantId, String reason);

    /** Story 24.9 — SUSPENDED (with this reason) → ACTIVE, trace cleared, one statement. @return ids activated */
    Set<UUID> activateSuspended(Collection<UUID> tenantIds, String reason);

    /** Story 24.9 — PENDING invitations re-dated (now, now + validity, message window open). @return rows updated */
    int redateInvitations(Collection<UUID> invitationIds, Duration validity);

    /** Story 24.9 — marks a SUSPENDED (with this reason) tenant DELETION_PENDING, scheduled far ahead. */
    com.keevo.identity.api.LegacyIdentityImportPort.RemovalPreparation markForRemoval(UUID tenantId, String reason);

    /** Story 24.9 — removes the memberships of these accounts in the tenant. */
    void removeMemberships(UUID tenantId, Collection<UUID> accountIds);

    /** Story 24.9 — deletes these Google / Apple identities. */
    void deleteAuthenticators(Collection<UUID> authenticatorIds);

    /** Story 24.9 — deletes these accounts when no membership holds them (reset tokens first). @return deleted */
    int deleteOrphanAccounts(Collection<UUID> accountIds);
}
