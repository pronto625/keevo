package com.keevo.identity.api;

import java.time.Instant;
import java.util.Collection;
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
 * <p>Linking a Bref owner to an existing Keevo account (phone, then verified email) is the identity bridge of 24.7:
 * here a match is only reported ({@link #findConflicts}) and the importer blocks the owner.
 */
public interface LegacyIdentityImportPort {

    /**
     * The identifiers of {@code accounts} already held by Keevo accounts, in one query (index lookups on id, phone,
     * {@code lower(email)}, {@code lower(username)}, and username = an existing phone). An account whose Keevo row has
     * its own id reports {@link ConflictKind#SAME_ID} only (a previous import, or an id collision: the caller decides
     * with its migration records).
     *
     * @return account id → the kinds found; accounts without any match are absent
     */
    Map<UUID, Set<ConflictKind>> findConflicts(Collection<LegacyAccount> accounts);

    /**
     * Creates the owner's account (absent id only), then a new tenant named {@code tenantName}, {@code SUSPENDED},
     * first plan event {@code MIGRATION_BREF}, no placeholder store, then the OWNER membership and role.
     */
    ImportedTenant createOwnerTenant(LegacyAccount owner, String tenantName);

    /**
     * The imported tenant's profile: preferences (sector, reports and alerts off, sale flow left to
     * {@link TenantSaleFlowCommandPort}) when absent, and the suspension trace (date, reason) when not set.
     *
     * @param sectorType onboarding sector code (e.g. {@code ELECTRONICS}); {@code null} = none
     * @return whether the preferences row was created by this call
     */
    boolean prepareImportedProfile(String tenantSchema, UUID tenantId, String sectorType, String suspensionReason);

    /**
     * Imports the members of a tenant: accounts (absent ids), memberships (absent (user, tenant) pairs), employee
     * records (id = account id, absent ids). Three JDBC batches, whatever the number of members.
     */
    MemberImportResult importMembers(String tenantSchema, UUID tenantId, List<ImportedMember> members);

    /** What makes an imported identifier unusable in Keevo. */
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
        USERNAME_IS_PHONE
    }

    /**
     * An account as imported.
     *
     * @param phoneNumber     E.164 or {@code null}
     * @param passwordHash    bcrypt hash copied as is, {@code null} = sign-in by code only
     * @param emailVerifiedAt {@code null} = not verified
     * @param phoneVerifiedAt {@code null} = not verified
     */
    record LegacyAccount(UUID id, String phoneNumber, String email, String username, String passwordHash,
                         String firstName, String lastName, Instant emailVerifiedAt, Instant phoneVerifiedAt,
                         Instant createdAt, Instant updatedAt) {

        public LegacyAccount {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        /** No secret in logs or test output. */
        @Override
        public String toString() {
            return "LegacyAccount[" + id + "]";
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
     */
    record ImportedMember(LegacyAccount account, Role role, UUID storeId, boolean active,
                          boolean passwordChangeRequired, String firstName, String lastName) {

        public ImportedMember {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(storeId, "storeId");
            if (role == null || !role.isStoreMember()) {
                throw new IllegalArgumentException("role must be MANAGER, EMPLOYEE or VIEWER");
            }
        }
    }

    /**
     * What one {@link #importMembers} call inserted (rows already there are not listed).
     *
     * @param usersInserted       account ids inserted
     * @param membershipsInserted account id → new membership id
     * @param employeesInserted   employee ids inserted (= account ids)
     */
    record MemberImportResult(Set<UUID> usersInserted, Map<UUID, UUID> membershipsInserted,
                              Set<UUID> employeesInserted) {
    }
}
