package com.keevo.migration.application.step;

import com.keevo.migration.application.ImportRun;
import com.keevo.migration.application.ImportStep;
import com.keevo.migration.application.MigrationUnitOfWork;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.ImportNote;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.port.out.BrefLogoFetcher;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.api.StoreImportPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_SHOP;
import static com.keevo.migration.domain.model.MigrationRecord.STORE_LOGOS;

/**
 * Story 24.2, §10.2 step 2 (logos) — each shop logo not imported yet: the bytes embedded in the export, else a download
 * of {@code photoUrl} (https, allowed hosts, when switched on), then {@code StoreLogoUseCase} through the store import
 * port (size check, re-encoding, storage, audit) and its record, in one tenant transaction per logo. The download runs
 * outside any transaction. A logo that cannot be brought over is noted, never fails the import.
 */
@Component
@Order(40)
class LogosStep implements ImportStep {

    private static final Logger log = LoggerFactory.getLogger(LogosStep.class);

    private final StoreImportPort stores;
    private final BrefLogoFetcher fetcher;
    private final MigrationRecordStore records;
    private final MigrationUnitOfWork uow;

    LogosStep(StoreImportPort stores, BrefLogoFetcher fetcher, MigrationRecordStore records, MigrationUnitOfWork uow) {
        this.stores = stores;
        this.fetcher = fetcher;
        this.records = records;
        this.uow = uow;
    }

    @Override
    public String label() {
        return "logos des boutiques";
    }

    @Override
    public java.util.Set<String> targetTables() {
        return java.util.Set.of(STORE_LOGOS);
    }

    @Override
    public void run(ImportRun run) {
        String schema = run.tenantSchema();
        for (BrefShop shop : run.snapshot().shops()) {
            String url = BrefMappings.trimmed(shop.photoUrl());
            if (shop.photo() == null && url == null) {
                continue;
            }
            if (run.imported(BREF_SHOP, shop.id(), STORE_LOGOS)) {
                run.count(ImportRun.ALREADY_IMPORTED, 1);
                continue;
            }
            byte[] image = shop.photo();
            if (image == null) {
                if (!fetcher.enabled() || !fetcher.allowed(url)) {
                    run.note(ImportNote.Kind.LOGO_NOT_FETCHED);
                    continue;
                }
                image = fetcher.fetch(url).orElse(null);
                if (image == null) {
                    run.note(ImportNote.Kind.LOGO_FAILED);
                    continue;
                }
            }
            byte[] bytes = image;
            MigrationRecord record = new MigrationRecord(STORE_LOGOS, shop.id(), BREF_SHOP, shop.id(),
                    Canonical.sha256(bytes), null);
            try {
                uow.tenant(schema, () -> {
                    stores.importLogo(schema, shop.id(), bytes, run.actorId());
                    records.insert(run.batchId(), run.ownerId(), List.of(record));
                    return null;
                });
                run.remember(List.of(record));
                run.count(ImportRun.LOGOS_IMPORTED, 1);
            } catch (DomainException e) {
                // too large / not an image: kept out, the owner can set a logo after activation
                log.info("Bref import: logo of store {} refused ({})", shop.id(), e.getDomainCode());
                run.note(ImportNote.Kind.LOGO_FAILED);
            } catch (RuntimeException e) {
                // storage failure (I/O): the logo transaction rolled back, the import goes on
                log.warn("Bref import: logo of store {} not stored ({})", shop.id(), e.getClass().getSimpleName());
                run.note(ImportNote.Kind.LOGO_FAILED);
            }
        }
    }
}
