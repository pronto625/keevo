package com.keevo.migration.application.step;

import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.store.api.StoreImportPort;
import com.keevo.store.api.StoreImportPort.ImportedStore;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_SHOP;
import static com.keevo.migration.domain.model.MigrationRecord.STORES;

/**
 * Story 24.2, §10.2 step 2 — every shop of the owner as a store with the same id, original dates and Bref fields
 * (deleted or closed shops inactive, so 24.4's documents keep their store), one batch insert and its records in one
 * transaction bound to the tenant. An invalid shop phone is not kept (noted); client sharing stays off (ADR-08).
 */
@Component
@Order(30)
class StoresStep implements ImportStep {

    private final StoreImportPort stores;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;

    StoresStep(StoreImportPort stores, MigrationRecordStore records, MigrationUnitOfWork uow) {
        this.stores = stores;
        this.records = records;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "boutiques";
    }

    @Override
    public java.util.Set<String> targetTables() {
        return java.util.Set.of(STORES);
    }

    @Override
    public void run(ImportRun run) {
        List<ImportedStore> inputs = new ArrayList<>();
        Map<UUID, String> notes = new LinkedHashMap<>();
        Map<UUID, BrefShop> shops = new LinkedHashMap<>();
        for (BrefShop shop : run.snapshot().shops()) {
            shops.put(shop.id(), shop);
            List<String> rowNotes = new ArrayList<>();
            if (shop.deleted()) {
                run.note(ImportNote.Kind.SHOP_DELETED);
                rowNotes.add(ImportNote.Kind.SHOP_DELETED.label());
            } else if (!shop.active()) {
                run.note(ImportNote.Kind.SHOP_INACTIVE);
                rowNotes.add(ImportNote.Kind.SHOP_INACTIVE.label());
            }
            if (BrefMappings.invalidPhone(shop.phone())) {
                run.note(ImportNote.Kind.SHOP_PHONE_DROPPED);
                rowNotes.add(ImportNote.Kind.SHOP_PHONE_DROPPED.label());
            }
            if (!rowNotes.isEmpty()) {
                notes.put(shop.id(), String.join(" ; ", rowNotes));
            }
            if (run.imported(BREF_SHOP, shop.id(), STORES)) {
                run.count(ImportRun.ALREADY_IMPORTED, 1);
                continue;
            }
            inputs.add(new ImportedStore(shop.id(), shop.name(), BrefMappings.trimmed(shop.address()),
                    BrefMappings.e164(shop.phone()), BrefMappings.trimmed(shop.category()), shop.description(),
                    shop.niu(), shop.rccm(), BrefMappings.trimmed(shop.country()), shop.active() && !shop.deleted(),
                    shop.createdAt(), shop.updatedAt()));
        }
        if (inputs.isEmpty()) {
            return;
        }
        String schema = run.tenantSchema();
        List<MigrationRecord> written = uow.tenant(schema, () -> {
            List<MigrationRecord> rows = new ArrayList<>();
            for (UUID id : stores.importStores(schema, inputs)) {
                rows.add(new MigrationRecord(STORES, id, BREF_SHOP, id,
                        Canonical.sha256(shops.get(id).canonical()), notes.get(id)));
            }
            records.insert(run.batchId(), run.ownerId(), rows);
            return rows;
        });
        run.remember(written); // after commit
        run.count(ImportRun.STORES_CREATED, written.size());
        run.count(ImportRun.ALREADY_IMPORTED, inputs.size() - written.size());
    }
}
