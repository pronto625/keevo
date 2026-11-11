package com.keevo.migration.application.step;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.LegacyIdentityImportPort.ImportedTenant;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantView;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.IdentityResolution;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;
import static com.keevo.migration.domain.model.MigrationRecord.LINKED_USERS;
import static com.keevo.migration.domain.model.MigrationRecord.MEMBERSHIPS;
import static com.keevo.migration.domain.model.MigrationRecord.TENANTS;
import static com.keevo.migration.domain.model.MigrationRecord.USERS;

/**
 * Story 24.2, §10.2 step 1 — the owner's account (Bref id, bcrypt hash) and the tenant (named after the owner's
 * oldest shop, suspended until activation, no « Ma Boutique », plan event {@code MIGRATION_BREF}), with the OWNER
 * membership and role, in one platform transaction together with their records. A run that finds the tenant record
 * reuses that tenant (the orchestrator already refused a tenant that no longer exists). Story 24.7: an owner the
 * identity bridge linked to an existing Keevo account gets the OWNER membership on that account (no account written,
 * record {@code linked_users}); the Google / Apple identity comes in {@code SocialAccountsStep}.
 */
@Component
@Order(10)
class IdentityStep implements ImportStep {

    private static final int MAX_TENANT_NAME = 100;

    private final LegacyIdentityImportPort identity;
    private final TenantQueryPort tenants;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;

    IdentityStep(LegacyIdentityImportPort identity, TenantQueryPort tenants, MigrationRecordStore records,
                 MigrationUnitOfWork uow) {
        this.identity = identity;
        this.tenants = tenants;
        this.records = records;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "compte et entreprise";
    }

    @Override
    public java.util.Set<String> targetTables() {
        return java.util.Set.of(TENANTS, USERS, MEMBERSHIPS, LINKED_USERS);
    }

    @Override
    public void run(ImportRun run) {
        BrefAccount owner = run.snapshot().owner();
        UUID existing = run.target(BREF_USER, owner.id(), TENANTS);
        if (existing != null) {
            TenantView tenant = tenants.findById(existing)
                    .orElseThrow(() -> new IllegalStateException("Imported tenant missing"));
            run.tenant(tenant.id(), tenant.schemaName());
            int known = 1;
            for (String table : List.of(USERS, MEMBERSHIPS, LINKED_USERS)) {
                known += run.imported(BREF_USER, owner.id(), table) ? 1 : 0;
            }
            run.count(ImportRun.ALREADY_IMPORTED, known);
            return;
        }
        String provider = BrefMappings.trimmed(owner.provider());
        if (provider != null && !"email".equalsIgnoreCase(provider) && BrefMappings.socialProvider(owner) == null) {
            run.note(ImportNote.Kind.OWNER_SOCIAL_SIGN_IN); // a Google / Apple identity is imported by SocialAccountsStep
        }
        // Story 24.7 — an owner already in Keevo keeps his account: the company is added to it (decision D1)
        IdentityResolution resolution = run.identity(owner.id());
        UUID linked = resolution.action() == IdentityResolution.Action.LINK ? resolution.keevoAccountId() : null;
        String checksum = Canonical.sha256(owner.canonical());
        record Created(ImportedTenant tenant, List<MigrationRecord> written) {
        }
        Created created = uow.platform(() -> {
            ImportedTenant tenant = identity.createOwnerTenant(BrefMappings.toLegacy(owner), linked,
                    tenantName(run.snapshot().shops(), owner));
            List<MigrationRecord> written = new ArrayList<>();
            written.add(new MigrationRecord(TENANTS, tenant.tenantId(), BREF_USER, owner.id(), checksum, null));
            if (tenant.ownerInserted()) {
                written.add(new MigrationRecord(USERS, owner.id(), BREF_USER, owner.id(), checksum, null));
            }
            if (linked != null && !run.imported(BREF_USER, owner.id(), LINKED_USERS)) {
                written.add(new MigrationRecord(LINKED_USERS, linked, BREF_USER, owner.id(), checksum,
                        ImportNote.Kind.OWNER_LINKED.label()));
            }
            if (tenant.ownerMembershipId() != null) {
                written.add(new MigrationRecord(MEMBERSHIPS, tenant.ownerMembershipId(), BREF_USER, owner.id(),
                        checksum, null));
            }
            records.insert(run.batchId(), run.ownerId(), written);
            return new Created(tenant, written);
        });
        // the run changes only once the transaction committed
        ImportedTenant tenant = created.tenant();
        run.remember(created.written());
        run.tenant(tenant.tenantId(), tenant.schemaName());
        run.count(ImportRun.TENANTS_CREATED, 1);
        run.count(ImportRun.USERS_CREATED, tenant.ownerInserted() ? 1 : 0);
        run.count(ImportRun.MEMBERSHIPS_CREATED, tenant.ownerMembershipId() != null ? 1 : 0);
        if (linked != null) {
            run.count(ImportRun.ACCOUNTS_LINKED, 1);
            run.note(ImportNote.Kind.OWNER_LINKED);
        }
    }

    /** The oldest live shop's name (the oldest shop when all are deleted), else the owner's name. */
    static String tenantName(List<BrefShop> shops, BrefAccount owner) {
        String name = shops.stream()
                .sorted(Comparator.comparing(BrefShop::deleted).thenComparing(BrefShop::createdAt)
                        .thenComparing(BrefShop::id))
                .map(s -> BrefMappings.trimmed(s.name()))
                .filter(n -> n != null)
                .findFirst()
                .orElseGet(() -> {
                    String full = ((owner.firstName() == null ? "" : owner.firstName()) + " "
                            + (owner.lastName() == null ? "" : owner.lastName())).strip();
                    return full.isEmpty() ? null : full;
                });
        if (name != null && name.codePointCount(0, name.length()) > MAX_TENANT_NAME) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_TENANT_NAME)).strip(); // never half a character
        }
        return name;
    }
}
