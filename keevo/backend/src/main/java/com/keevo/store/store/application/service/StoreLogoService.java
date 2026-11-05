package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.store.StoreUpdatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.StoreLogoUseCase;
import com.keevo.store.store.domain.port.out.StoreLogoNormalizer;
import com.keevo.store.store.domain.port.out.StoreLogoStorage;
import com.keevo.store.store.domain.port.out.StoreLogoStorage.StoredLogo;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Store logo use case (Story 19.12) — Ports &amp; Adapters: the bytes go through {@link StoreLogoNormalizer} and
 * {@link StoreLogoStorage}, the row keeps only the file name. O(1): one store read, one update, one file write.
 *
 * <p>Each upload is a new file (unique name), written under a lock on the store row ({@code FOR UPDATE}). The file the
 * row no longer points to is deleted only after the commit; the new file is deleted after a rollback: a rolled-back
 * change never leaves the row pointing to a missing or foreign file, nor an orphan.
 */
@Service
public class StoreLogoService implements StoreLogoUseCase {

    private final StoreRepository storeRepository;
    private final StoreLogoStorage storage;
    private final StoreLogoNormalizer normalizer;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxBytes;

    public StoreLogoService(StoreRepository storeRepository, StoreLogoStorage storage, StoreLogoNormalizer normalizer,
                            ApplicationEventPublisher eventPublisher,
                            @Value("${keevo.store-logo.max-bytes:204800}") int maxBytes) {
        this.storeRepository = storeRepository;
        this.storage = storage;
        this.normalizer = normalizer;
        this.eventPublisher = eventPublisher;
        this.maxBytes = maxBytes;
    }

    @Override
    @Transactional
    public Store upload(UUID storeId, byte[] upload, UUID actorId) {
        String schema = TenantContext.requireCurrent();
        // cheap checks and the re-encoding first: no database work for a refused upload
        if (upload == null || upload.length == 0) {
            throw new DomainException(ErrorCode.STORE_LOGO_INVALID, "Empty store logo");
        }
        if (upload.length > maxBytes) {
            throw new DomainException(ErrorCode.STORE_LOGO_TOO_LARGE,
                    "Store logo of " + upload.length + " bytes over " + maxBytes);
        }
        StoredLogo logo = normalizer.normalize(upload);
        Store existing = findForUpdate(storeId); // serialises logo changes of this store until commit
        String fileName;
        try {
            fileName = storage.store(schema, storeId, logo.content(), logo.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Store logo could not be stored", e);
        }
        // the new file goes if the transaction rolls back; the previous one once the row points to the new one
        String previous = existing.logoPath();
        onCompletion(() -> {
            if (previous != null && !previous.equals(fileName)) storage.delete(schema, previous);
        }, () -> storage.delete(schema, fileName));
        Store saved = storeRepository.save(existing.withLogoPath(fileName, Instant.now()));
        publish(saved, existing.hasLogo() ? "Remplacé" : "Ajouté", actorId, schema);
        return saved;
    }

    @Override
    @Transactional
    public Store delete(UUID storeId, UUID actorId) {
        String schema = TenantContext.requireCurrent();
        Store existing = findForUpdate(storeId);
        if (!existing.hasLogo()) {
            return existing;
        }
        Store saved = storeRepository.save(existing.withLogoPath(null, Instant.now()));
        String previous = existing.logoPath();
        onCompletion(() -> storage.delete(schema, previous), () -> { });
        publish(saved, "Supprimé", actorId, schema);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public StoredLogo read(UUID storeId) {
        String schema = TenantContext.requireCurrent();
        Store store = find(storeId);
        if (!store.hasLogo()) {
            throw notFound();
        }
        try {
            return storage.read(schema, store.logoPath()).orElseThrow(StoreLogoService::notFound);
        } catch (IOException e) {
            throw new UncheckedIOException("Store logo could not be read", e);
        }
    }

    private Store find(UUID storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND, "Boutique introuvable"));
    }

    private Store findForUpdate(UUID storeId) {
        return storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new DomainException(ErrorCode.STORE_NOT_FOUND, "Boutique introuvable"));
    }

    private void publish(Store saved, String change, UUID actorId, String schema) {
        eventPublisher.publishEvent(new StoreUpdatedEvent(saved.id(), saved.name(), actorId, schema, Instant.now(),
                Map.of(), Map.of("logo", change)));
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.STORE_LOGO_NOT_FOUND, "No store logo");
    }

    /**
     * Runs {@code onCommit} after a commit and {@code onRollback} after a rollback; without a transaction (unit use)
     * the commit branch runs at once.
     */
    private static void onCompletion(Runnable onCommit, Runnable onRollback) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) onCommit.run();
                    else if (status == STATUS_ROLLED_BACK) onRollback.run();
                }
            });
        } else {
            onCommit.run();
        }
    }
}
