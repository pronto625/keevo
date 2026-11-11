package com.keevo.migration.application.step;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.LegacyIdentityImportPort.ImportedMember;
import com.keevo.identity.api.LegacyIdentityImportPort.MemberImportResult;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.Canonical;
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
import static com.keevo.migration.domain.model.MigrationRecord.MEMBERSHIPS;
import static com.keevo.migration.domain.model.MigrationRecord.USERS;

/**
 * Story 24.2, §10.2 step 3 — the team: every live member of the owner's shops becomes an account (Bref id, bcrypt hash
 * copied), a membership (admin → MANAGER, seller → EMPLOYEE, viewer → VIEWER) and an employee record on the shop's
 * store; three batches and their records in one tenant transaction. Deleted members are skipped and counted; a
 * deactivated member arrives deactivated.
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
        return java.util.Set.of(USERS, MEMBERSHIPS, EMPLOYEES);
    }

    @Override
    public void run(ImportRun run) {
        List<ImportedMember> members = new ArrayList<>();
        Map<UUID, String> checksums = new HashMap<>();
        int alreadyThere = 0;
        for (BrefAccount m : run.snapshot().members()) {
            if (m.deleted()) {
                run.count(ImportRun.MEMBERS_SKIPPED_DELETED, 1);
                run.note(ImportNote.Kind.MEMBER_DELETED);
                continue;
            }
            if (!m.active()) {
                run.note(ImportNote.Kind.MEMBER_INACTIVE);
            }
            if (BrefMappings.trimmed(m.passwordHash()) == null) {
                run.note(ImportNote.Kind.MEMBER_NO_PASSWORD);
            }
            boolean user = run.imported(BREF_USER, m.id(), USERS);
            boolean membership = run.imported(BREF_USER, m.id(), MEMBERSHIPS);
            boolean employee = run.imported(BREF_USER, m.id(), EMPLOYEES);
            if (user && membership && employee) {
                alreadyThere += 3;
                continue;
            }
            checksums.put(m.id(), Canonical.sha256(m.canonical()));
            members.add(new ImportedMember(BrefMappings.toLegacy(m), BrefMappings.memberRole(m.role()).orElseThrow(),
                    UUID.fromString(m.shopId().strip()), m.active(), m.mustChangePassword(),
                    BrefMappings.employeeFirstName(m), BrefMappings.employeeLastName(m)));
        }
        run.count(ImportRun.ALREADY_IMPORTED, alreadyThere);
        if (members.isEmpty()) {
            return;
        }
        String schema = run.tenantSchema();
        record Imported(MemberImportResult result, List<MigrationRecord> written) {
        }
        Imported imported = uow.tenant(schema, () -> {
            MemberImportResult result = identity.importMembers(schema, run.tenantId(), members);
            List<MigrationRecord> written = new ArrayList<>();
            result.usersInserted().forEach(id ->
                    written.add(new MigrationRecord(USERS, id, BREF_USER, id, checksums.get(id), null)));
            result.membershipsInserted().forEach((userId, membershipId) ->
                    written.add(new MigrationRecord(MEMBERSHIPS, membershipId, BREF_USER, userId,
                            checksums.get(userId), null)));
            result.employeesInserted().forEach(id ->
                    written.add(new MigrationRecord(EMPLOYEES, id, BREF_USER, id, checksums.get(id), null)));
            records.insert(run.batchId(), run.ownerId(), written);
            return new Imported(result, written);
        });
        MemberImportResult result = imported.result(); // the run changes only after commit
        run.remember(imported.written());
        run.count(ImportRun.USERS_CREATED, result.usersInserted().size());
        run.count(ImportRun.MEMBERSHIPS_CREATED, result.membershipsInserted().size());
        run.count(ImportRun.EMPLOYEES_CREATED, result.employeesInserted().size());
        run.count(ImportRun.ALREADY_IMPORTED, 3 * members.size() - imported.written().size());
    }
}
