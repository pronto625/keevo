package com.keevo.migration.application.step;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.LegacyIdentityImportPort.ImportedMember;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyInvitation;
import com.keevo.identity.api.LegacyIdentityImportPort.MemberImportResult;
import com.keevo.identity.api.Role;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.IdentityResolution;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;
import static com.keevo.migration.domain.model.MigrationRecord.EMPLOYEES;
import static com.keevo.migration.domain.model.MigrationRecord.INVITATIONS;
import static com.keevo.migration.domain.model.MigrationRecord.LINKED_USERS;
import static com.keevo.migration.domain.model.MigrationRecord.MEMBERSHIPS;
import static com.keevo.migration.domain.model.MigrationRecord.USERS;

/**
 * Story 24.2, §10.2 step 3 — the team: every live member of the owner's shops becomes an account (Bref id, bcrypt hash
 * copied), a membership (admin → MANAGER, seller → EMPLOYEE, viewer → VIEWER) and an employee record on the shop's
 * store; set-based statements and their records in one tenant transaction. Deleted members are skipped and counted; a
 * deactivated member arrives deactivated.
 *
 * <p>Story 24.7 — the identity bridge's decision per member: a member already in Keevo with a proven identity keeps
 * his Keevo account (membership and employee record on it, the employee keeps the Bref id, no password change asked),
 * one matched by an unconfirmed phone is invited (19.6b, PENDING, no message), one deactivated in Bref is left out; an
 * email held by another account without proof is not carried. The Keevo account behind each Bref member is recorded
 * ({@code linked_users}) so that later steps map Bref references to it ({@link ImportRun#accountId}).
 */
@Component
@Order(50)
class MembersStep implements ImportStep {

    private final LegacyIdentityImportPort identity;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;

    MembersStep(LegacyIdentityImportPort identity, MigrationRecordStore records, MigrationUnitOfWork uow) {
        this.identity = identity;
        this.records = records;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "membres de l'équipe";
    }

    @Override
    public java.util.Set<String> targetTables() {
        return java.util.Set.of(USERS, MEMBERSHIPS, EMPLOYEES, LINKED_USERS, INVITATIONS);
    }

    @Override
    public void run(ImportRun run) {
        List<ImportedMember> members = new ArrayList<>();
        List<LegacyInvitation> invitations = new ArrayList<>();
        Map<UUID, String> checksums = new HashMap<>();
        Map<UUID, UUID> brefIdOfInvited = new HashMap<>();
        int alreadyThere = 0;
        int expectedRows = 0;
        for (BrefAccount m : run.snapshot().members()) {
            if (m.deleted()) {
                run.count(ImportRun.MEMBERS_SKIPPED_DELETED, 1);
                run.note(ImportNote.Kind.MEMBER_DELETED);
                continue;
            }
            IdentityResolution resolution = run.identity(m.id());
            switch (resolution.action()) {
                case SKIP -> {
                    run.note(ImportNote.Kind.MEMBER_INACTIVE_NOT_INVITED);
                    continue;
                }
                case INVITE -> {
                    run.note(ImportNote.Kind.MEMBER_INVITED);
                    if (run.imported(BREF_USER, m.id(), INVITATIONS) && run.imported(BREF_USER, m.id(), LINKED_USERS)) {
                        alreadyThere += 2;
                        continue;
                    }
                    checksums.put(m.id(), Canonical.sha256(m.canonical()));
                    brefIdOfInvited.put(resolution.keevoAccountId(), m.id());
                    invitations.add(new LegacyInvitation(resolution.keevoAccountId(), role(m), shop(m),
                            BrefMappings.cut(BrefMappings.employeeFirstName(m), BrefMappings.MAX_NAME),
                            BrefMappings.cut(BrefMappings.employeeLastName(m), BrefMappings.MAX_NAME),
                            BrefMappings.e164(m.phone())));
                    expectedRows += 2;
                    continue;
                }
                default -> {
                    // CREATE or LINK below (BLOCK never reaches the steps)
                }
            }
            boolean linked = resolution.action() == IdentityResolution.Action.LINK;
            if (linked) {
                run.note(ImportNote.Kind.MEMBER_LINKED);
            } else {
                if (BrefMappings.trimmed(m.passwordHash()) == null) {
                    run.note(ImportNote.Kind.MEMBER_NO_PASSWORD);
                }
                if (resolution.dropEmail()) {
                    run.note(ImportNote.Kind.MEMBER_EMAIL_DROPPED);
                }
            }
            if (!m.active()) {
                run.note(ImportNote.Kind.MEMBER_INACTIVE);
            }
            boolean account = run.imported(BREF_USER, m.id(), linked ? LINKED_USERS : USERS);
            boolean membership = run.imported(BREF_USER, m.id(), MEMBERSHIPS);
            boolean employee = run.imported(BREF_USER, m.id(), EMPLOYEES);
            if (account && membership && employee) {
                alreadyThere += 3;
                continue;
            }
            checksums.put(m.id(), Canonical.sha256(m.canonical()));
            LegacyAccount legacy = BrefMappings.toLegacy(m);
            if (resolution.dropEmail()) {
                legacy = legacy.withoutEmail();
            }
            // a linked member keeps his Keevo password: no change asked
            members.add(new ImportedMember(legacy, role(m), shop(m), m.active(), !linked && m.mustChangePassword(),
                    BrefMappings.cut(BrefMappings.employeeFirstName(m), BrefMappings.MAX_NAME),
                    BrefMappings.cut(BrefMappings.employeeLastName(m), BrefMappings.MAX_NAME),
                    linked ? resolution.keevoAccountId() : null)); // a linked member skips the Bref name rule

            expectedRows += 3;
        }
        run.count(ImportRun.ALREADY_IMPORTED, alreadyThere);
        if (members.isEmpty() && invitations.isEmpty()) {
            return;
        }
        String schema = run.tenantSchema();
        record Imported(MemberImportResult result, Map<UUID, UUID> invited, int linkedAccounts,
                        List<MigrationRecord> written) {
        }
        Imported imported = uow.tenant(schema, () -> {
            MemberImportResult result = identity.importMembers(schema, run.tenantId(), members);
            // the inviter is the company's owner (his acceptance notice goes to him, 19.6b), never the operator
            Map<UUID, UUID> invited = identity.inviteMembers(run.tenantId(), run.accountId(run.ownerId()),
                    invitations);
            List<MigrationRecord> written = new ArrayList<>();
            result.usersInserted().forEach(id ->
                    written.add(new MigrationRecord(USERS, id, BREF_USER, id, checksums.get(id), null)));
            result.membershipsInserted().forEach((brefId, membershipId) ->
                    written.add(new MigrationRecord(MEMBERSHIPS, membershipId, BREF_USER, brefId,
                            checksums.get(brefId), null)));
            result.employeesInserted().forEach(id ->
                    written.add(new MigrationRecord(EMPLOYEES, id, BREF_USER, id, checksums.get(id), null)));
            int linkedAccounts = 0;
            for (ImportedMember member : members) {
                UUID brefId = member.account().id();
                if (member.linkedAccountId() != null && !run.imported(BREF_USER, brefId, LINKED_USERS)) {
                    written.add(new MigrationRecord(LINKED_USERS, member.linkedAccountId(), BREF_USER, brefId,
                            checksums.get(brefId), ImportNote.Kind.MEMBER_LINKED.label()));
                    linkedAccounts++;
                }
            }
            invited.forEach((keevoId, invitationId) -> {
                UUID brefId = brefIdOfInvited.get(keevoId);
                written.add(new MigrationRecord(INVITATIONS, invitationId, BREF_USER, brefId, checksums.get(brefId),
                        ImportNote.Kind.MEMBER_INVITED.label()));
            });
            for (LegacyInvitation invitation : invitations) {
                UUID brefId = brefIdOfInvited.get(invitation.accountId());
                // only with its invitation: a lone linked_users record would read as a link on a re-run
                if (invited.containsKey(invitation.accountId()) && !run.imported(BREF_USER, brefId, LINKED_USERS)) {
                    written.add(new MigrationRecord(LINKED_USERS, invitation.accountId(), BREF_USER, brefId,
                            checksums.get(brefId), ImportNote.Kind.MEMBER_INVITED.label()));
                }
            }
            records.insert(run.batchId(), run.ownerId(), written);
            return new Imported(result, invited, linkedAccounts, written);
        });
        MemberImportResult result = imported.result(); // the run changes only after commit
        run.remember(imported.written());
        run.count(ImportRun.USERS_CREATED, result.usersInserted().size());
        run.count(ImportRun.MEMBERSHIPS_CREATED, result.membershipsInserted().size());
        run.count(ImportRun.EMPLOYEES_CREATED, result.employeesInserted().size());
        run.count(ImportRun.ACCOUNTS_LINKED, imported.linkedAccounts());
        run.count(ImportRun.MEMBERS_INVITED, imported.invited().size());
        run.count(ImportRun.ALREADY_IMPORTED, expectedRows - imported.written().size());
    }

    private static Role role(BrefAccount m) {
        return BrefMappings.memberRole(m.role()).orElseThrow();
    }

    private static UUID shop(BrefAccount m) {
        return UUID.fromString(m.shopId().strip());
    }
}
