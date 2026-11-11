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

import java.util.Collection;
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
 * (no welcome, no {@code UserRegisteredEvent}), and the tenant is suspended until its wave activates it (24.9).
 *
 * <p>Complexity: {@link #findConflicts} one query; {@link #createOwnerTenant} the registration's statements plus two
 * inserts; {@link #importMembers} three JDBC batches whatever the number of members.
 */
@Service
public class LegacyIdentityImportService implements LegacyIdentityImportPort {

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
    public Map<UUID, Set<ConflictKind>> findConflicts(Collection<LegacyAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return Map.of();
        }
        return store.findConflicts(accounts);
    }

    @Override
    public ImportedTenant createOwnerTenant(LegacyAccount owner, String tenantName) {
        Objects.requireNonNull(owner, "owner");
        boolean ownerInserted = !store.insertAccounts(List.of(owner), List.of(Role.OWNER), List.of(true)).isEmpty();
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
        Map<UUID, UUID> membership = store.insertMemberships(tenant.getId(), List.of(owner), List.of(Role.OWNER),
                List.of(true));
        tenantSchemaPort.assignOwnerRole(tenant.getSchemaName(), owner.id());
        return new ImportedTenant(tenant.getId(), tenant.getSchemaName(), tenant.getCode(), ownerInserted,
                membership.get(owner.id()));
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
        List<LegacyAccount> accounts = members.stream().map(ImportedMember::account).toList();
        List<Role> roles = members.stream().map(ImportedMember::role).toList();
        List<Boolean> active = members.stream().map(ImportedMember::active).toList();
        Set<UUID> users = store.insertAccounts(accounts, roles, active);
        Map<UUID, UUID> memberships = store.insertMemberships(tenantId, accounts, roles, active);
        Set<UUID> employees = store.insertEmployees(schema, members);
        return new MemberImportResult(users, memberships, employees);
    }
}
