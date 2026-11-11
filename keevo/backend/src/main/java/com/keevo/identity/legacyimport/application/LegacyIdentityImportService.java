package com.keevo.identity.legacyimport.application;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.Role;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantSchemaPort;
import com.keevo.identity.legacyimport.application.port.LegacyIdentityStore;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.2 — implements {@link LegacyIdentityImportPort} for the Bref migration tool.
 *
 * <p>Pattern: Facade over the identity internals the import needs ({@link TenantFactory} with import options,
 * {@link TenantSchemaPort#assignOwnerRole}, the {@link LegacyIdentityStore} batches). No {@code @Transactional}: the
 * caller (the migration step) owns the unit of work. No event is published — an imported owner is not a registration
 * (no welcome, no {@code UserRegisteredEvent}), and the tenant is suspended until its wave activates it (24.9). Story
 * 24.7: a linked account is only given a membership (and an employee record), never rewritten; an invitation is
 * recorded without its message (19.6b's message goes out only from the owner's own « add member »).
 *
 * <p>Complexity: {@link #findMatches} one query; {@link #createOwnerTenant} the registration's statements plus two
 * inserts; {@link #importMembers} three set-based statements; {@link #inviteMembers} and
 * {@link #importAuthenticators} one statement each — whatever the number of rows.
 */
@Service
public class LegacyIdentityImportService implements LegacyIdentityImportPort {

    /** Story 24.7 — the validity of an imported invitation: 19.6b's (7 days, {@code MembershipInvitationService}). */
    static final Duration INVITATION_VALIDITY = Duration.ofDays(7);

    private final LegacyIdentityStore store;
    private final TenantFactory tenantFactory;
    private final TenantSchemaPort tenantSchemaPort;
    private final TenantSchemaProvisioner schemaProvisioner;

    public LegacyIdentityImportService(LegacyIdentityStore store, TenantFactory tenantFactory,
                                       TenantSchemaPort tenantSchemaPort, TenantSchemaProvisioner schemaProvisioner) {
        this.store = store;
        this.tenantFactory = tenantFactory;
        this.tenantSchemaPort = tenantSchemaPort;
        this.schemaProvisioner = schemaProvisioner;
    }

    @Override
    public Map<UUID, List<AccountMatch>> findMatches(Collection<LegacyAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return Map.of();
        }
        return store.findMatches(accounts);
    }

    @Override
    public ImportedTenant createOwnerTenant(LegacyAccount owner, UUID linkedAccountId, String tenantName) {
        Objects.requireNonNull(owner, "owner");
        // a linked owner keeps his Keevo account as is: no insert, the membership goes to that account
        LegacyAccount member = linkedAccountId == null ? owner : owner.withId(linkedAccountId);
        boolean ownerInserted = linkedAccountId == null
                && !store.insertAccounts(List.of(owner), List.of(Role.OWNER), List.of(true)).isEmpty();
        Tenant tenant = tenantFactory.create(new TenantFactory.TenantCreation(tenantName, TenantStatus.SUSPENDED,
                PlanChangeCause.MIGRATION_BREF, false));
        // the schema was committed on its own connection: if the caller's transaction rolls back (a later insert of
        // this step fails), drop it so no orphan schema stays behind
        String schema = tenant.getSchemaName();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        schemaProvisioner.dropSchemaIfExists(schema);
                    }
                }
            });
        }
        Map<UUID, UUID> membership = store.insertMemberships(tenant.getId(), List.of(member), List.of(Role.OWNER),
                List.of(true));
        tenantSchemaPort.assignOwnerRole(tenant.getSchemaName(), member.id());
        return new ImportedTenant(tenant.getId(), tenant.getSchemaName(), tenant.getCode(), ownerInserted,
                membership.get(member.id()));
    }

    @Override
    public boolean prepareImportedProfile(String tenantSchema, UUID tenantId, String sectorType,
                                          String suspensionReason) {
        String schema = TenantSchema.validate(tenantSchema);
        boolean created = store.insertPreferencesIfAbsent(schema, sectorType);
        store.traceSuspension(tenantId, suspensionReason);
        return created;
    }

    @Override
    public MemberImportResult importMembers(String tenantSchema, UUID tenantId, List<ImportedMember> members) {
        String schema = TenantSchema.validate(tenantSchema);
        if (members == null || members.isEmpty()) {
            return new MemberImportResult(Set.of(), Map.of(), Set.of());
        }
        List<ImportedMember> created = members.stream().filter(m -> m.linkedAccountId() == null).toList();
        Set<UUID> users = store.insertAccounts(created.stream().map(ImportedMember::account).toList(),
                created.stream().map(ImportedMember::role).toList(),
                created.stream().map(ImportedMember::active).toList());
        // memberships belong to the Keevo account (the linked one for a linked member); the result speaks Bref ids
        Map<UUID, UUID> importedIdOf = new HashMap<>();
        members.forEach(m -> importedIdOf.put(m.userId(), m.account().id()));
        Map<UUID, UUID> byUser = store.insertMemberships(tenantId,
                members.stream().map(m -> m.account().withId(m.userId())).toList(),
                members.stream().map(ImportedMember::role).toList(),
                members.stream().map(ImportedMember::active).toList());
        Map<UUID, UUID> memberships = new LinkedHashMap<>();
        byUser.forEach((userId, membershipId) -> memberships.put(importedIdOf.get(userId), membershipId));
        Set<UUID> employees = store.insertEmployees(schema, members);
        return new MemberImportResult(users, memberships, employees);
    }

    @Override
    public Map<UUID, UUID> inviteMembers(UUID tenantId, UUID invitedBy, List<LegacyInvitation> invitations) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (invitations == null || invitations.isEmpty()) {
            return Map.of();
        }
        return store.insertInvitations(tenantId, invitedBy, invitations, INVITATION_VALIDITY);
    }

    @Override
    public List<AuthenticatorOutcome> importAuthenticators(List<SocialIdentity> identities) {
        if (identities == null || identities.isEmpty()) {
            return List.of();
        }
        return store.insertAuthenticators(identities);
    }

    @Override
    public Set<UUID> activateImportedTenants(Collection<UUID> tenantIds, String suspensionReason) {
        Objects.requireNonNull(suspensionReason, "suspensionReason");
        if (tenantIds == null || tenantIds.isEmpty()) {
            return Set.of();
        }
        return store.activateSuspended(tenantIds, suspensionReason);
    }

    @Override
    public int redateImportedInvitations(Collection<UUID> invitationIds) {
        if (invitationIds == null || invitationIds.isEmpty()) {
            return 0;
        }
        return store.redateInvitations(invitationIds, INVITATION_VALIDITY);
    }

    @Override
    public RemovalPreparation prepareImportedTenantRemoval(UUID tenantId, String suspensionReason,
                                                           Collection<UUID> linkedAccountIds,
                                                           Collection<UUID> authenticatorIds) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(suspensionReason, "suspensionReason");
        RemovalPreparation found = store.markForRemoval(tenantId, suspensionReason);
        if (found == RemovalPreparation.READY && linkedAccountIds != null && !linkedAccountIds.isEmpty()) {
            store.removeMemberships(tenantId, linkedAccountIds);
        }
        if (found != RemovalPreparation.REFUSED && authenticatorIds != null && !authenticatorIds.isEmpty()) {
            store.deleteAuthenticators(authenticatorIds);
        }
        return found;
    }

    @Override
    public int deleteOrphanImportedAccounts(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        return store.deleteOrphanAccounts(accountIds);
    }
}
