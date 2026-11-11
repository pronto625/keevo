package com.keevo.migration.application.step;

import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.service.BrefDocumentPlanner;
import com.keevo.shared.capability.portal.InvoicePhoneIndex;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_INVOICE;
import static com.keevo.migration.domain.model.MigrationRecord.INVOICES;

/**
 * Story 24.6, §10.2 step 11 — the client-portal index (item routed by the 21.7 review: the 21.7 back-fill runs once,
 * imported invoices must be indexed by the import): every invoice of the owner recorded as imported — not a quote, not
 * deleted, with a client phone — goes to {@link InvoicePhoneIndex#indexAll} with the values 24.4 wrote (phone key,
 * {@code issued_at} = Bref {@code validatedAt ?: createdAt}). Every run, whatever wrote the invoices: the index upserts
 * only what differs, so a re-run writes nothing and a run interrupted after the documents is completed. Invalid phones
 * are skipped by the index (as for issued invoices).
 *
 * <p>Cost: O(n) over the snapshot's invoices; ⌈n / {@value #BATCH}⌉ short platform transactions, one batch upsert each
 * (plus the index's tenant lookup).
 */
@Component
@Order(130)
class PortalIndexStep implements ImportStep {

    static final String INDEXED = "portalInvoicesIndexed";
    static final int BATCH = 1000;

    private final InvoicePhoneIndex index;
    private final MigrationUnitOfWork uow;

    PortalIndexStep(InvoicePhoneIndex index, MigrationUnitOfWork uow) {
        this.index = index;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "index du portail client";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(INVOICES);
    }

    @Override
    public void run(ImportRun run) {
        run.count(INDEXED, 0);
        List<InvoicePhoneIndex.Entry> entries = entries(run);
        String schema = run.tenantSchema();
        for (int from = 0; from < entries.size(); from += BATCH) {
            List<InvoicePhoneIndex.Entry> batch = entries.subList(from, Math.min(entries.size(), from + BATCH));
            int written = uow.platform(() -> index.indexAll(schema, batch));
            run.count(INDEXED, written);
        }
    }

    /** The portal entries of the imported invoices (the 21.7 back-fill's filter on the Keevo side). */
    static List<InvoicePhoneIndex.Entry> entries(ImportRun run) {
        List<InvoicePhoneIndex.Entry> entries = new ArrayList<>();
        for (BrefInvoice i : run.snapshot().invoices()) {
            if (i.quote() || i.deleted() || !run.imported(BREF_INVOICE, i.id(), INVOICES)) {
                continue;
            }
            String phone = BrefDocumentPlanner.phoneKey(i.customerPhone());
            if (phone != null) {
                entries.add(new InvoicePhoneIndex.Entry(i.id(), phone,
                        i.validatedAt() != null ? i.validatedAt() : i.createdAt()));
            }
        }
        return entries;
    }
}
