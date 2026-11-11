package com.keevo.migration.application.step;

import com.keevo.catalog.api.CatalogImportPort;
import com.keevo.catalog.api.CatalogImportPort.ImportedCategories;
import com.keevo.identity.api.SectorTemplatePort;
import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;
import static com.keevo.migration.domain.model.MigrationRecord.CATEGORIES;

/**
 * Story 24.3, §10.2 step 4 — the categories an imported company starts with (it has no onboarding, so no
 * {@code TenantOnboardedEvent}): the root categories of its sector's onboarding template (sector = the oldest shop's,
 * as the profile step chose it) and « Produits importés », which every imported product belongs to. Once per tenant
 * (decision D6): the « Produits importés » category is recorded against the owner, a run that finds the record writes
 * nothing — an owner's later changes to his categories are never undone.
 */
@Component
@Order(60)
class CategoriesStep implements ImportStep {

    /** The category of every imported product. */
    static final String IMPORTED_CATEGORY = "Produits importés";

    private final SectorTemplatePort templates;
    private final CatalogImportPort catalog;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;

    CategoriesStep(SectorTemplatePort templates, CatalogImportPort catalog, MigrationRecordStore records,
                   MigrationUnitOfWork uow) {
        this.templates = templates;
        this.catalog = catalog;
        this.records = records;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "catégories";
    }

    @Override
    public Set<String> targetTables() {
        return Set.of(CATEGORIES);
    }

    @Override
    public void run(ImportRun run) {
        if (run.imported(BREF_USER, run.ownerId(), CATEGORIES)) {
            run.count(ImportRun.ALREADY_IMPORTED, 1);
            return;
        }
        String sector = BrefMappings.ownerSector(run.snapshot().shops());
        List<String> names = sector == null ? List.of() : templates.templateCategories(sector);
        String schema = run.tenantSchema();
        record Seeded(ImportedCategories categories, MigrationRecord record) {
        }
        Seeded seeded = uow.tenant(schema, () -> {
            ImportedCategories categories = catalog.seedImportCategories(schema, names, IMPORTED_CATEGORY);
            MigrationRecord record = new MigrationRecord(CATEGORIES, categories.importedCategoryId(), BREF_USER,
                    run.ownerId(), Canonical.sha256(run.snapshot().owner().canonical()),
                    "Catégorie « " + IMPORTED_CATEGORY + " »");
            records.insert(run.batchId(), run.ownerId(), List.of(record));
            return new Seeded(categories, record);
        });
        run.remember(List.of(seeded.record())); // after commit
        run.count(ImportRun.CATEGORIES_CREATED, seeded.categories().created());
    }
}
