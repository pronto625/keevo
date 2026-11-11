package com.keevo.identity.api;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — identity writes of the Bref migration tool ({@code migration} module): accounts imported with their Bref
 * id and bcrypt hash, the owner's tenant (suspended until the wave activates it, without the « Ma Boutique »
 * placeholder), the imported profile and the members. Every write is idempotent ({@code ON CONFLICT DO NOTHING}) and
 * set-based; none publishes an event or notifies anyone. Every method joins the caller's transaction.
 *
 * <p>Story 24.7 — identity bridge: {@link #findMatches} names the Keevo accounts holding an imported identifier and the
 * importer decides (create, link, invite or block). {@link #createOwnerTenant(LegacyAccount, UUID, String)} and
 * {@link #importMembers} then attach an existing account instead of creating one (its password, identifiers and
 * {@code users.role} are never touched), {@link #inviteMembers} records a pending invitation (19.6b, no message) and
 * {@link #importAuthenticators} brings the Google / Apple identities.
 */
public interface LegacyIdentityImportPort {

    /**
     * The identifiers of {@code accounts} already held by Keevo accounts (kinds only, see {@link #findMatches}). An
     * account whose Keevo row has its own id reports {@link ConflictKind#SAME_ID} (a previous import, or an id
     * collision: the caller decides with its migration records).
     *
     * @return account id → the kinds found; accounts without any match are absent
     */
    default Map<UUID, Set<ConflictKind>> findConflicts(Collection<LegacyAccount> accounts) {
        Map<UUID, Set<ConflictKind>> kinds = new LinkedHashMap<>();
        findMatches(accounts).forEach((id, matches) -> matches.forEach(m ->
                kinds.computeIfAbsent(id, k -> EnumSet.noneOf(ConflictKind.class)).add(m.kind())));
        return kinds;
    }

    /**
     * Story 24.7 — every Keevo account holding an identifier of {@code accounts} (id, phone, email, username, the
     * phone read from a numeric username, the phone used as a username, the Google / Apple identity), with what the
     * bridge needs to decide. One query over the unnested input, one index lookup per identifier kind.
     *
     * @return account id → its matches; accounts without any match are absent
     */
    Map<UUID, List<AccountMatch>> findMatches(Collection<LegacyAccount> accounts);

    /**
     * Creates the owner's account (absent id only), then a new tenant named {@code tenantName}, {@code SUSPENDED},
     * first plan event {@code MIGRATION_BREF}, no placeholder store, then the OWNER membership and role.
     */
    default ImportedTenant createOwnerTenant(LegacyAccount owner, String tenantName) {
        return createOwnerTenant(owner, null, tenantName);
    }

    /**
     * Story 24.7 — same as {@link #createOwnerTenant(LegacyAccount, String)}; with {@code linkedAccountId} no account
     * is inserted and the OWNER membership and role go to that existing Keevo account (its password, identifiers and
     * {@code users.role} stay as they are). The membership keeps the Bref account's dates.
     */
    ImportedTenant createOwnerTenant(LegacyAccount owner, UUID linkedAccountId, String tenantName);

    /**
     * The imported tenant's profile: preferences (sector, reports and alerts off, sale flow left to
     * {@link TenantSaleFlowCommandPort}) when absent, and the suspension trace (date, reason) when not set.
     *
     * @param sectorType onboarding sector code (e.g. {@code ELECTRONICS}); {@code null} = none
     * @return whether the preferences row was created by this call
     */
    boolean prepareImportedProfile(String tenantSchema, UUID tenantId, String sectorType, String suspensionReason);

    /**
     * Imports the members of a tenant: accounts (absent ids; none for a member linked to an existing account),
     * memberships (absent (user, tenant) pairs), employee records (id = the imported account id, absent ids). Three
     * set-based statements, whatever the number of members.
     */
    MemberImportResult importMembers(String tenantSchema, UUID tenantId, List<ImportedMember> members);

    /**
     * Story 24.7 — pending invitations (19.6b) of existing Keevo accounts to the imported tenant, identified by the
     * phone the owner had typed in Bref. No message is sent: the person sees the invitation once the company is
     * active. Absent (tenant, account) pairs only; one statement.
     *
     * @return Keevo account id → invitation id, for the invitations inserted
     */
    Map<UUID, UUID> inviteMembers(UUID tenantId, UUID invitedBy, List<LegacyInvitation> invitations);

    /**
     * Story 24.7 — the Google / Apple identities of imported or linked accounts, in one statement: an identity is
     * inserted when no account holds it and its account has no identity of that provider yet.
     *
     * @return one outcome per input, in input order
     */
    List<AuthenticatorOutcome> importAuthenticators(List<SocialIdentity> identities);

    /**
     * Story 24.9 — the « go » of the Bref cutover, in one statement: the imported companies still {@code SUSPENDED} with
     * {@code suspensionReason} (the import's own) become {@code ACTIVE} and their suspension trace is cleared. No event,
     * no message; a company suspended for another reason, or already active, is left as is.
     *
     * @return the ids of the companies activated by this call
     */
    Set<UUID> activateImportedTenants(Collection<UUID> tenantIds, String suspensionReason);

    /**
     * Story 24.9 — the invitations the import created (19.6b, PENDING) are re-dated at the « go », in one statement:
     * invited now, valid {@code validity} from now, and the 19.6b message window re-opened ({@code last_sent_at} one day
     * back, so the owner may re-send at once). No message is sent.
     *
     * @return the number of invitations re-dated
     */
    int redateImportedInvitations(Collection<UUID> invitationIds);

    /**
     * Story 24.9 — prepares the removal of an imported company by the cutover's rollback, in one transaction: the
     * company is marked {@code DELETION_PENDING} (scheduled far ahead: the daily deletion never takes it) only when it is
     * still {@code SUSPENDED} with {@code suspensionReason} (or already being removed), the memberships of the existing
     * Keevo accounts the import linked are removed (their accounts are never deleted), and the Google / Apple identities
     * the import added are deleted.
     *
     * @param linkedAccountIds  the accounts recorded {@code linked_users} by the import
     * @param authenticatorIds  the identities recorded {@code user_authenticators} by the import
     */
    RemovalPreparation prepareImportedTenantRemoval(UUID tenantId, String suspensionReason,
                                                    Collection<UUID> linkedAccountIds,
                                                    Collection<UUID> authenticatorIds);

    /**
     * Story 24.9 — deletes the accounts the import created ({@code users} records) that no company holds any more
     * (their reset tokens first); an account member of another company is kept.
     *
     * @return the number of accounts deleted
     */
    int deleteOrphanImportedAccounts(Collection<UUID> accountIds);

    /** Story 24.9 — what {@link #prepareImportedTenantRemoval} found. */
    enum RemovalPreparation {
        /** Marked for removal: {@link TenantDeletionPort#forgetImportedNow} may run. */
        READY,
        /** The company no longer exists (an earlier rollback removed it). */
        ABSENT,
        /** The company is active (or suspended for another reason): it is not removed. */
        REFUSED
    }

    /** What makes an imported identifier unusable in Keevo as is. */
    enum ConflictKind {
        /** A Keevo account already has this id. */
        SAME_ID,
        /** Another Keevo account has this phone. */
        PHONE,
        /** Another Keevo account has this email (case-insensitive). */
        EMAIL,
        /** Another Keevo account has this username (case-insensitive). */
        USERNAME,
        /** The username is the phone of another Keevo account (login ambiguity). */
        USERNAME_IS_PHONE,
        /** Story 24.7 — the phone is the username of another Keevo account (login ambiguity). */
        PHONE_IS_USERNAME,
        /** Story 24.7 — the Google / Apple identity is linked to another Keevo account. */
        SOCIAL
    }

    /**
     * An account as imported.
     *
     * @param phoneNumber     E.164 or {@code null}
     * @param passwordHash    bcrypt hash copied as is, {@code null} = sign-in by code only
     * @param emailVerifiedAt {@code null} = not verified
     * @param phoneVerifiedAt {@code null} = not verified
     * @param socialProvider  Story 24.7 — {@code GOOGLE} or {@code APPLE}, {@code null} = none
     * @param socialSubject   Story 24.7 — the provider's user id, {@code null} = none
     */
    record LegacyAccount(UUID id, String phoneNumber, String email, String username, String passwordHash,
                         String firstName, String lastName, Instant emailVerifiedAt, Instant phoneVerifiedAt,
                         Instant createdAt, Instant updatedAt, String socialProvider, String socialSubject) {

        public LegacyAccount {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if ((socialProvider == null) != (socialSubject == null)) {
                throw new IllegalArgumentException("social provider and subject go together");
            }
        }

        /** An account without Google / Apple identity (24.2 form). */
        public LegacyAccount(UUID id, String phoneNumber, String email, String username, String passwordHash,
                             String firstName, String lastName, Instant emailVerifiedAt, Instant phoneVerifiedAt,
                             Instant createdAt, Instant updatedAt) {
            this(id, phoneNumber, email, username, passwordHash, firstName, lastName, emailVerifiedAt,
                    phoneVerifiedAt, createdAt, updatedAt, null, null);
        }

        /** Story 24.7 — the same account under another id (the membership of a linked Keevo account). */
        public LegacyAccount withId(UUID otherId) {
            return new LegacyAccount(otherId, phoneNumber, email, username, passwordHash, firstName, lastName,
                    emailVerifiedAt, phoneVerifiedAt, createdAt, updatedAt, socialProvider, socialSubject);
        }

        /** Story 24.7 — the same account without its email (held by another Keevo account, never proven). */
        public LegacyAccount withoutEmail() {
            return new LegacyAccount(id, phoneNumber, null, username, passwordHash, firstName, lastName, null,
                    phoneVerifiedAt, createdAt, updatedAt, socialProvider, socialSubject);
        }

        /** No secret in logs or test output. */
        @Override
        public String toString() {
            return "LegacyAccount[" + id + "]";
        }
    }

    /**
     * Story 24.7 — a Keevo account holding an identifier of an imported account.
     *
     * @param userId        the Keevo account
     * @param active        {@code users.is_active}
     * @param emailVerified the Keevo account's email is verified
     * @param platformAdmin the platform administrator (never linked)
     */
    record AccountMatch(ConflictKind kind, UUID userId, boolean active, boolean emailVerified,
                        boolean platformAdmin) {

        public AccountMatch {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * The tenant created for an imported owner.
     *
     * @param ownerInserted     whether the owner's account was inserted by this call (absent id)
     * @param ownerMembershipId the OWNER membership inserted by this call
     */
    record ImportedTenant(UUID tenantId, String schemaName, String code, boolean ownerInserted,
                          UUID ownerMembershipId) {
    }

    /**
     * A member to import.
     *
     * @param role                   MANAGER, EMPLOYEE or VIEWER
     * @param storeId                the member's store (already imported)
     * @param active                 {@code false} = membership inactive, employee {@code INACTIVE}
     * @param passwordChangeRequired the employee must change the password at next sign-in
     * @param linkedAccountId        Story 24.7 — the existing Keevo account the member is attached to ({@code null}
     *                               = the account is created with the Bref id); the employee record keeps the Bref id
     */
    record ImportedMember(LegacyAccount account, Role role, UUID storeId, boolean active,
                          boolean passwordChangeRequired, String firstName, String lastName, UUID linkedAccountId) {

        public ImportedMember {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(storeId, "storeId");
            if (role == null || !role.isStoreMember()) {
                throw new IllegalArgumentException("role must be MANAGER, EMPLOYEE or VIEWER");
            }
        }

        /** A member whose account is created (24.2 form). */
        public ImportedMember(LegacyAccount account, Role role, UUID storeId, boolean active,
                              boolean passwordChangeRequired, String firstName, String lastName) {
            this(account, role, storeId, active, passwordChangeRequired, firstName, lastName, null);
        }

        /** Story 24.7 — the Keevo account the membership and the employee record belong to. */
        public UUID userId() {
            return linkedAccountId != null ? linkedAccountId : account.id();
        }
    }

    /**
     * What one {@link #importMembers} call inserted (rows already there are not listed).
     *
     * @param usersInserted       account ids inserted
     * @param membershipsInserted imported account id (the Bref id, also for a linked member) → new membership id
     * @param employeesInserted   employee ids inserted (= imported account ids)
     */
    record MemberImportResult(Set<UUID> usersInserted, Map<UUID, UUID> membershipsInserted,
                              Set<UUID> employeesInserted) {
    }

    /**
     * Story 24.7 — an invitation of an existing account (19.6b) by the phone typed in Bref.
     *
     * @param role MANAGER, EMPLOYEE or VIEWER
     */
    record LegacyInvitation(UUID accountId, Role role, UUID storeId, String firstName, String lastName,
                            String phoneNumber) {

        public LegacyInvitation {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(phoneNumber, "phoneNumber");
            if (role == null || !role.isStoreMember()) {
                throw new IllegalArgumentException("role must be MANAGER, EMPLOYEE or VIEWER");
            }
        }

        /** No personal data in logs or test output. */
        @Override
        public String toString() {
            return "LegacyInvitation[" + accountId + ", " + role + "]";
        }
    }

    /**
     * Story 24.7 — a Google / Apple identity to attach to a Keevo account.
     *
     * @param provider {@code GOOGLE} or {@code APPLE}
     * @param subject  the provider's stable user id (ID token {@code sub})
     */
    record SocialIdentity(UUID userId, String provider, String subject) {

        public SocialIdentity {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(subject, "subject");
        }

        @Override
        public String toString() {
            return "SocialIdentity[" + userId + ", " + provider + "]";
        }
    }

    /**
     * Story 24.7 — what {@link #importAuthenticators} did with one identity.
     *
     * @param authenticatorId the row inserted ({@link Result#INSERTED} only)
     */
    record AuthenticatorOutcome(SocialIdentity identity, Result result, UUID authenticatorId) {

        public enum Result {
            /** Inserted for the account. */
            INSERTED,
            /** The account already holds this identity. */
            ALREADY_LINKED,
            /** The account already holds another identity of the same provider: left as is. */
            PROVIDER_ALREADY_LINKED,
            /** Another account holds this identity: left as is. */
            HELD_BY_OTHER
        }
    }
}
