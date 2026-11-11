package com.keevo.migration.application.step;

import com.keevo.invoicing.api.InvoiceImportPort;
import com.keevo.invoicing.api.InvoiceImportPort.Document;
import com.keevo.invoicing.api.InvoiceImportPort.Line;
import com.keevo.invoicing.api.InvoiceImportPort.Payment;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.application.ModuleTables;
import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.BrefProduct;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefDocumentPlanner;
import com.keevo.migration.domain.service.BrefDocumentPlanner.PlannedDocument;
import com.keevo.migration.domain.service.BrefDocumentPlanner.Row;
import com.keevo.shared.domain.time.KeevoZones;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_CUSTOMER;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_INVOICE;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_PRODUCT;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_SHOP;
import static com.keevo.migration.domain.model.MigrationRecord.CLIENTS;
import static com.keevo.migration.domain.model.MigrationRecord.INVOICES;
import static com.keevo.migration.domain.model.MigrationRecord.INVOICE_ITEMS;
import static com.keevo.migration.domain.model.MigrationRecord.INVOICE_PAYMENTS;
import static com.keevo.migration.domain.model.MigrationRecord.INVOICING_SETTINGS;
import static com.keevo.migration.domain.model.MigrationRecord.PRODUCTS;

/**
 * Story 24.4, §10.2 step 7 — the documents: the seller settings of the tenant (oldest live shop, once — item routed by
 * 24.2; public links 90 days — item routed by 20.7), then quotes and invoices with their lines and payments, planned
 * by {@link BrefDocumentPlanner} (Bref values kept, anomalies noted) and written through the invoicing import port
 * (no issuing, numbering counter, quota nor event; {@code payment_state} computed by invoicing; old public links
 * recorded) — only the rows not recorded yet, in one tenant transaction with their records. The invoicing tables are
 * created first whatever the plan ({@link ModuleTables}).
 *
 * <p>Cost: the plan in memory (O(n log n)); one Flyway history read, then 1 settings + 1 preferences insert (first run
 * only), 3 inserts, 2 reads + 1 update for the states, 1 registry insert, 1 records insert.
 */
@Component
@Order(90)
class DocumentsStep implements ImportStep {

    /** Counter keys of the batch (stable: the dashboard reads them). */
    static final String QUOTES_CREATED = "quotesCreated";
    static final String INVOICES_CREATED = "invoicesCreated";
    static final String INVOICE_LINES_CREATED = "invoiceLinesCreated";
    static final String PAYMENTS_CREATED = "paymentsCreated";
    static final String INVOICING_MODULE = "invoicing";

    private final InvoiceImportPort invoicing;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;
    private final ModuleTables tables;
    private final Clock clock;

    DocumentsStep(InvoiceImportPort invoicing, MigrationRecordStore records, MigrationUnitOfWork uow,
                  ModuleTables tables, Clock clock) {
        this.invoicing = invoicing;
        this.records = records;
        this.uow = uow;
        this.tables = tables;
        this.clock = clock;
    }

    @Override
    public String label() {
        return "documents";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(INVOICES, INVOICE_ITEMS, INVOICE_PAYMENTS, INVOICING_SETTINGS, PRODUCTS, CLIENTS);
    }

    @Override
    public void run(ImportRun run) {
        for (String key : List.of(QUOTES_CREATED, INVOICES_CREATED, INVOICE_LINES_CREATED, PAYMENTS_CREATED)) {
            run.count(key, 0);
        }
        BrefDocumentPlanner.Plan plan = BrefDocumentPlanner.plan(run.snapshot().shops(), run.snapshot().invoices(),
                run.ownerId(), lookup(run));
        BrefShop settingsShop = plan.settingsShop();
        boolean settingsDone = settingsShop == null || run.imported(BREF_SHOP, settingsShop.id(), INVOICING_SETTINGS);
        if (settingsDone) {
            run.count(ImportRun.ALREADY_IMPORTED, settingsShop == null ? 0 : 1);
        }

        List<Document> documents = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        List<Payment> payments = new ArrayList<>();
        List<MigrationRecord> pending = new ArrayList<>();
        Map<UUID, List<ImportNote.Kind>> documentNotes = new HashMap<>();
        Map<UUID, List<ImportNote.Kind>> lineNotes = new HashMap<>();
        Map<UUID, List<ImportNote.Kind>> paymentNotes = new HashMap<>();
        int already = 0;
        for (PlannedDocument d : plan.documents()) {
            UUID id = d.source().id();
            if (run.imported(BREF_INVOICE, id, INVOICES)) {
                already++;
            } else {
                documents.add(d.document());
                documentNotes.put(id, d.notes());
                pending.add(new MigrationRecord(INVOICES, id, BREF_INVOICE, id,
                        Canonical.sha256(d.source().canonical()), d.note()));
            }
            for (Row<Line> l : d.lines()) {
                if (run.imported(l.sourceTable(), l.sourceId(), INVOICE_ITEMS)) {
                    already++;
                    continue;
                }
                lines.add(l.row());
                lineNotes.put(l.row().id(), l.notes());
                pending.add(new MigrationRecord(INVOICE_ITEMS, l.row().id(), l.sourceTable(), l.sourceId(),
                        Canonical.sha256(l.canonical()), l.note()));
            }
            for (Row<Payment> p : d.payments()) {
                if (run.imported(p.sourceTable(), p.sourceId(), INVOICE_PAYMENTS)) {
                    already++;
                    continue;
                }
                payments.add(p.row());
                paymentNotes.put(p.row().id(), p.notes());
                pending.add(new MigrationRecord(INVOICE_PAYMENTS, p.row().id(), p.sourceTable(), p.sourceId(),
                        Canonical.sha256(p.canonical()), p.note()));
            }
        }
        run.count(ImportRun.ALREADY_IMPORTED, already);
        if (settingsDone && pending.isEmpty()) {
            return;
        }
        if (!settingsDone) {
            pending.add(new MigrationRecord(INVOICING_SETTINGS, settingsShop.id(), BREF_SHOP, settingsShop.id(),
                    Canonical.sha256(settingsShop.canonical()), null));
        }
        String schema = run.tenantSchema();
        tables.ensure(schema, INVOICING_MODULE);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, KeevoZones.WAT);
        InvoiceImportPort.Result result = uow.tenant(schema, () -> {
            if (!settingsDone) {
                invoicing.importSettings(schema, plan.settings(), now);
            }
            InvoiceImportPort.Result r = invoicing.importDocuments(schema, documents, lines, payments, today);
            // a row already there (written by a run whose record is missing) is recorded too: it has this id
            records.insert(run.batchId(), run.ownerId(), pending);
            return r;
        });
        run.remember(pending); // after commit
        // notes of the rows this run wrote only (a row already there with its record missing is not noted again)
        result.documents().forEach(id -> documentNotes.getOrDefault(id, List.of()).forEach(run::note));
        result.lines().forEach(id -> lineNotes.getOrDefault(id, List.of()).forEach(run::note));
        result.payments().forEach(id -> paymentNotes.getOrDefault(id, List.of()).forEach(run::note));
        int quotes = (int) documents.stream().filter(d -> d.quote() && result.documents().contains(d.id())).count();
        run.count(QUOTES_CREATED, quotes);
        run.count(INVOICES_CREATED, result.documents().size() - quotes);
        run.count(INVOICE_LINES_CREATED, result.lines().size());
        run.count(PAYMENTS_CREATED, result.payments().size());
        run.count(ImportRun.ALREADY_IMPORTED, documents.size() + lines.size() + payments.size()
                - result.documents().size() - result.lines().size() - result.payments().size());
    }

    /**
     * Keevo ids the documents point to: the product of each Bref product key per shop (24.3 records — a Bref product
     * merged in Bref keeps its own archived row, the line cites it as Bref did) and the client of each phone per shop
     * (ADR-08: per establishment).
     */
    private static BrefDocumentPlanner.Lookup lookup(ImportRun run) {
        Map<String, UUID> products = new HashMap<>();
        for (BrefProduct p : run.snapshot().products()) {
            UUID target = run.target(BREF_PRODUCT, p.id(), PRODUCTS);
            if (target != null && p.productKey() != null) {
                products.putIfAbsent(BrefDocumentPlanner.shopKey(p.shopId(), p.productKey()), target);
            }
        }
        Map<String, UUID> clients = new HashMap<>();
        for (BrefCustomer c : run.snapshot().customers()) {
            UUID target = run.target(BREF_CUSTOMER, c.id(), CLIENTS);
            String phone = BrefDocumentPlanner.phoneKey(c.phone());
            if (target != null && phone != null) {
                clients.putIfAbsent(BrefDocumentPlanner.shopKey(c.shopId(), phone), target);
            }
        }
        return new BrefDocumentPlanner.Lookup(products, clients);
    }
}
