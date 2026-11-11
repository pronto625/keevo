package com.keevo.migration.application.step;

import com.keevo.catalog.api.CatalogImportPort;
import com.keevo.catalog.api.CatalogImportPort.ImportedProduct;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefCatalogPlanner;
import com.keevo.migration.domain.service.BrefCatalogPlanner.CatalogPlan;
import com.keevo.migration.domain.service.BrefCatalogPlanner.PlannedProduct;
import com.keevo.migration.domain.service.BrefCatalogPlanner.SourceLink;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_PRODUCT;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;
import static com.keevo.migration.domain.model.MigrationRecord.CATEGORIES;
import static com.keevo.migration.domain.model.MigrationRecord.PRODUCTS;

/**
 * Story 24.3, §10.2 step 5 — the catalogue: the {@link BrefCatalogPlanner} plan of the whole snapshot (one product per
 * {@code productKey} across shops, Bref-merged products archived and redirected, unique names, integer prices), then,
 * in one tenant transaction, the products not imported yet (one insert, untracked, category « Produits importés ») and
 * one record per Bref product not recorded yet (the merged ones point to their group's product). A planned product
 * neither inserted nor present (a name taken by a row the plan does not know) fails the step: nothing is half-written.
 */
@Component
@Order(70)
class ProductsStep implements ImportStep {

    private final CatalogImportPort catalog;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;

    ProductsStep(CatalogImportPort catalog, MigrationRecordStore records, MigrationUnitOfWork uow) {
        this.catalog = catalog;
        this.records = records;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "produits";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(PRODUCTS, CATEGORIES);
    }

    @Override
    public void run(ImportRun run) {
        if (run.snapshot().products().isEmpty()) {
            return;
        }
        Set<UUID> imported = new HashSet<>();
        boolean missing = false;
        for (var p : run.snapshot().products()) {
            if (run.imported(BREF_PRODUCT, p.id(), PRODUCTS)) {
                imported.add(p.id());
            } else {
                missing = true;
            }
        }
        if (!missing) {
            run.count(ImportRun.ALREADY_IMPORTED, imported.size());
            return;
        }
        String schema = run.tenantSchema();
        // names held by stored products: a new product never takes one (stable names when Bref grows between runs)
        Set<String> taken = imported.isEmpty() ? Set.of() : uow.tenant(schema, () -> catalog.productNames(schema));
        CatalogPlan plan = BrefCatalogPlanner.plan(run.snapshot().products(), imported, taken);
        UUID category = run.target(BREF_USER, run.ownerId(), CATEGORIES);
        if (category == null) {
            throw new IllegalStateException("imported-products category missing"); // the categories step runs first
        }
        List<ImportedProduct> toInsert = new ArrayList<>();
        for (PlannedProduct p : plan.products()) {
            if (!run.imported(BREF_PRODUCT, p.id(), PRODUCTS)) {
                toInsert.add(new ImportedProduct(p.id(), p.name(), p.barcode(), p.productKey(), p.price(),
                        p.priceManual(), p.archived(), p.mergedIntoId(), category, p.createdAt(), p.updatedAt()));
            }
        }
        List<SourceLink> toRecord = plan.links().stream()
                .filter(l -> !run.imported(BREF_PRODUCT, l.source().id(), PRODUCTS)).toList();
        run.count(ImportRun.ALREADY_IMPORTED, plan.links().size() - toRecord.size());
        toRecord.forEach(link -> link.notes().forEach(run::note)); // notes of the rows written by this run only
        record Written(Set<UUID> inserted, List<MigrationRecord> records) {
        }
        Written written = uow.tenant(schema, () -> {
            Set<UUID> inserted = catalog.importProducts(schema, toInsert);
            Set<UUID> notInserted = toInsert.stream().map(ImportedProduct::id)
                    .filter(id -> !inserted.contains(id)).collect(Collectors.toCollection(HashSet::new));
            if (!notInserted.isEmpty()) {
                notInserted.removeAll(catalog.existingProducts(schema, notInserted));
                if (!notInserted.isEmpty()) {
                    throw new IllegalStateException(notInserted.size() + " product(s) refused by a unique rule");
                }
            }
            List<MigrationRecord> rows = new ArrayList<>(toRecord.size());
            for (SourceLink l : toRecord) {
                rows.add(new MigrationRecord(PRODUCTS, l.targetId(), BREF_PRODUCT, l.source().id(),
                        Canonical.sha256(l.source().canonical()), note(l.notes())));
            }
            records.insert(run.batchId(), run.ownerId(), rows);
            return new Written(inserted, rows);
        });
        run.remember(written.records()); // after commit
        run.count(ImportRun.PRODUCTS_CREATED, written.inserted().size());
        run.count(ImportRun.PRODUCTS_MERGED, (int) toRecord.stream()
                .filter(l -> l.notes().contains(ImportNote.Kind.PRODUCT_MERGED_BY_KEY)).count());
    }

    /** The French note of a record: the labels of its notes, {@code null} when none. */
    static String note(List<ImportNote.Kind> kinds) {
        return kinds.isEmpty() ? null : kinds.stream().map(ImportNote.Kind::label).collect(Collectors.joining(" ; "));
    }
}
