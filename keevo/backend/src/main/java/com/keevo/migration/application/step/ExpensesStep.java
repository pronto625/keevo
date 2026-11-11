package com.keevo.migration.application.step;

import com.keevo.expenses.api.ExpenseImportPort;
import com.keevo.expenses.api.ExpenseImportPort.ImportedExpense;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.application.ModuleTables;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefExpenseMapper;
import com.keevo.migration.domain.service.BrefExpenseMapper.MappedExpense;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_EXPENSE;
import static com.keevo.migration.domain.model.MigrationRecord.EXPENSES;

/**
 * Story 24.4, §10.2 step 8 — the expenses: every Bref expense not imported yet, mapped by {@link BrefExpenseMapper}
 * (exact amount, shop's store, keys shared with Bref), inserted through the expenses import port in one statement with
 * its records, in one tenant transaction. The expenses tables are created first whatever the plan
 * ({@link ModuleTables}). Cost: one Flyway history read, one insert, one records insert.
 */
@Component
@Order(100)
class ExpensesStep implements ImportStep {

    static final String EXPENSES_CREATED = "expensesCreated";
    static final String EXPENSES_MODULE = "expenses";

    private final ExpenseImportPort expenses;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;
    private final ModuleTables tables;

    ExpensesStep(ExpenseImportPort expenses, MigrationRecordStore records, MigrationUnitOfWork uow,
                 ModuleTables tables) {
        this.expenses = expenses;
        this.records = records;
        this.uow = uow;
        this.tables = tables;
    }

    @Override
    public String label() {
        return "dépenses";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(EXPENSES, MigrationRecord.USERS, MigrationRecord.LINKED_USERS, MigrationRecord.INVITATIONS);
    }

    @Override
    public void run(ImportRun run) {
        run.count(EXPENSES_CREATED, 0);
        List<ImportedExpense> toInsert = new ArrayList<>();
        List<MigrationRecord> pending = new ArrayList<>();
        java.util.Map<UUID, List<ImportNote.Kind>> notes = new java.util.HashMap<>();
        int already = 0;
        for (BrefExpense e : run.snapshot().expenses()) {
            if (run.imported(BREF_EXPENSE, e.id(), EXPENSES)) {
                already++;
                continue;
            }
            MappedExpense m = BrefExpenseMapper.map(e, DocumentsStep.ownerAccount(run), run::accountId);
            toInsert.add(m.expense());
            notes.put(e.id(), m.notes());
            pending.add(new MigrationRecord(EXPENSES, e.id(), BREF_EXPENSE, e.id(), Canonical.sha256(e.canonical()),
                    ProductsStep.note(m.notes())));
        }
        run.count(ImportRun.ALREADY_IMPORTED, already);
        if (toInsert.isEmpty()) {
            return;
        }
        String schema = run.tenantSchema();
        tables.ensure(schema, EXPENSES_MODULE);
        Set<UUID> inserted = uow.tenant(schema, () -> {
            Set<UUID> ids = expenses.importExpenses(schema, toInsert);
            records.insert(run.batchId(), run.ownerId(), pending);
            return ids;
        });
        run.remember(pending); // after commit
        inserted.forEach(id -> notes.getOrDefault(id, List.of()).forEach(run::note)); // rows written by this run only
        run.count(EXPENSES_CREATED, inserted.size());
        run.count(ImportRun.ALREADY_IMPORTED, toInsert.size() - inserted.size());
    }
}
