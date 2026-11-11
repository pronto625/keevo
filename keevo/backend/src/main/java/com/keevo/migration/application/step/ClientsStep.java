package com.keevo.migration.application.step;

import com.keevo.crm.api.ClientImportPort;
import com.keevo.crm.api.ClientImportPort.ImportedClient;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefClientMapper;
import com.keevo.migration.domain.service.BrefClientMapper.MappedClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_CUSTOMER;
import static com.keevo.migration.domain.model.MigrationRecord.CLIENTS;

/**
 * Story 24.3, §10.2 step 6 — the clients (ADR-08): every Bref customer becomes a Keevo client with the same id in the
 * store of its shop — the same phone in two shops stays two clients, store sharing stays off (24.2). Mapped by
 * {@link BrefClientMapper}; the clients not imported yet are inserted in one statement with their records, in one
 * tenant transaction.
 */
@Component
@Order(80)
class ClientsStep implements ImportStep {

    private final ClientImportPort clients;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;
    private final Clock clock;

    ClientsStep(ClientImportPort clients, MigrationRecordStore records, MigrationUnitOfWork uow, Clock clock) {
        this.clients = clients;
        this.records = records;
        this.uow = uow;
        this.clock = clock;
    }

    @Override
    public String label() {
        return "clients";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(CLIENTS);
    }

    @Override
    public void run(ImportRun run) {
        if (run.snapshot().customers().isEmpty()) {
            return;
        }
        Set<UUID> accounts = new HashSet<>();
        accounts.add(run.ownerId());
        run.snapshot().members().stream().map(BrefAccount::id).forEach(accounts::add);
        LocalDate today = BrefClientMapper.today(clock.instant());
        List<ImportedClient> toInsert = new ArrayList<>();
        Map<UUID, MigrationRecord> pending = new LinkedHashMap<>();
        int already = 0;
        for (BrefCustomer c : run.snapshot().customers()) {
            if (run.imported(BREF_CUSTOMER, c.id(), CLIENTS)) {
                already++;
                continue;
            }
            MappedClient m = BrefClientMapper.map(c, accounts, today);
            m.rowNotes().forEach(run::note); // notes of the rows written by this run only
            toInsert.add(new ImportedClient(m.id(), m.storeId(), m.name(), m.phone(), m.email(), m.notes(),
                    m.clientType(), m.niu(), m.rccm(), m.address(), m.contactName(), m.website(), m.birthDate(),
                    m.marketingConsentAt(), m.marketingConsentBy(), m.marketingOptOutAt(), m.createdAt(),
                    m.updatedAt()));
            pending.put(c.id(), new MigrationRecord(CLIENTS, c.id(), BREF_CUSTOMER, c.id(),
                    Canonical.sha256(c.canonical()), ProductsStep.note(m.rowNotes())));
        }
        run.count(ImportRun.ALREADY_IMPORTED, already);
        if (toInsert.isEmpty()) {
            return;
        }
        String schema = run.tenantSchema();
        List<MigrationRecord> written = uow.tenant(schema, () -> {
            Set<UUID> inserted = clients.importClients(schema, toInsert);
            // a client already there (written by a run whose record is missing) is recorded too: it has this id
            List<MigrationRecord> rows = new ArrayList<>(pending.values());
            records.insert(run.batchId(), run.ownerId(), rows);
            return rows.stream().filter(r -> inserted.contains(r.targetId())).toList();
        });
        run.remember(new ArrayList<>(pending.values())); // after commit
        run.count(ImportRun.CLIENTS_CREATED, written.size());
        run.count(ImportRun.ALREADY_IMPORTED, toInsert.size() - written.size());
    }
}
