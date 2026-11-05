package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.event.catalog.store.StoreUpdatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.out.StoreLogoNormalizer;
import com.keevo.store.store.domain.port.out.StoreLogoStorage;
import com.keevo.store.store.domain.port.out.StoreLogoStorage.StoredLogo;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreLogoService (Story 19.12)")
class StoreLogoServiceTest {

    private static final String SCHEMA = "kv_logo01";

    @Mock StoreRepository repository;
    @Mock StoreLogoStorage storage;
    @Mock StoreLogoNormalizer normalizer;
    @Mock ApplicationEventPublisher events;

    StoreLogoService service;
    UUID storeId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StoreLogoService(repository, storage, normalizer, events, 1000);
        TenantContext.setCurrentTenant(SCHEMA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Store store(String logoPath) {
        return new Store(storeId, "Boutique", StoreType.STORE, null, null, true, Instant.now(), Instant.now())
                .withLogoPath(logoPath, Instant.now());
    }

    private static String code(Throwable e) {
        return ((DomainException) e).getDomainCode();
    }

    @Test
    @DisplayName("upload: normalized, stored, row points to the file, audited « Ajouté »")
    void upload() throws Exception {
        when(repository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store(null)));
        when(normalizer.normalize(any())).thenReturn(new StoredLogo(new byte[]{7}, "image/png"));
        when(storage.store(SCHEMA, storeId, new byte[]{7}, "image/png")).thenReturn(storeId + ".png");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store saved = service.upload(storeId, new byte[]{1, 2}, actorId);

        assertThat(saved.logoPath()).isEqualTo(storeId + ".png");
        ArgumentCaptor<StoreUpdatedEvent> event = ArgumentCaptor.forClass(StoreUpdatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().after()).isEqualTo(Map.of("logo", "Ajouté"));
        verify(storage, never()).delete(any(), any());
    }

    @Test
    @DisplayName("replace PNG by JPEG: the old file is deleted (after commit), audited « Remplacé »")
    void replace() throws Exception {
        when(repository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store(storeId + ".png")));
        when(normalizer.normalize(any())).thenReturn(new StoredLogo(new byte[]{7}, "image/jpeg"));
        when(storage.store(SCHEMA, storeId, new byte[]{7}, "image/jpeg")).thenReturn(storeId + ".jpg");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upload(storeId, new byte[]{1}, actorId);

        verify(storage).delete(SCHEMA, storeId + ".png"); // no transaction here: runs at once
        ArgumentCaptor<StoreUpdatedEvent> event = ArgumentCaptor.forClass(StoreUpdatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().after()).isEqualTo(Map.of("logo", "Remplacé"));
    }

    @Test
    @DisplayName("empty / too big refused before any database read; unknown store → STORE_NOT_FOUND, nothing stored")
    void refusals() {
        assertThatThrownBy(() -> service.upload(storeId, new byte[1001], actorId))
                .satisfies(e -> assertThat(code(e)).isEqualTo("STORE_LOGO_TOO_LARGE"));
        assertThatThrownBy(() -> service.upload(storeId, new byte[0], actorId))
                .satisfies(e -> assertThat(code(e)).isEqualTo("STORE_LOGO_INVALID"));
        verifyNoInteractions(repository, normalizer, storage, events);

        when(normalizer.normalize(any())).thenReturn(new StoredLogo(new byte[]{7}, "image/png"));
        when(repository.findByIdForUpdate(storeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(storeId, new byte[]{1}, actorId))
                .satisfies(e -> assertThat(code(e)).isEqualTo("STORE_NOT_FOUND"));
        verifyNoInteractions(storage, events);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("in a transaction: commit deletes the previous file only; rollback deletes the new file only")
    void filesFollowTheTransactionOutcome() throws Exception {
        for (int status : new int[]{TransactionSynchronization.STATUS_COMMITTED,
                TransactionSynchronization.STATUS_ROLLED_BACK}) {
            reset(repository, storage, normalizer, events);
            when(repository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store(storeId + "-old.png")));
            when(normalizer.normalize(any())).thenReturn(new StoredLogo(new byte[]{7}, "image/png"));
            when(storage.store(SCHEMA, storeId, new byte[]{7}, "image/png")).thenReturn(storeId + "-new.png");
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            TransactionSynchronizationManager.initSynchronization();
            try {
                service.upload(storeId, new byte[]{1}, actorId);
                verify(storage, never()).delete(any(), any()); // nothing before the outcome
                for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                    sync.afterCompletion(status);
                }
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                verify(storage).delete(SCHEMA, storeId + "-old.png");
                verify(storage, never()).delete(SCHEMA, storeId + "-new.png");
            } else {
                verify(storage).delete(SCHEMA, storeId + "-new.png");
                verify(storage, never()).delete(SCHEMA, storeId + "-old.png");
            }
        }
    }

    @Test
    @DisplayName("delete: row cleared, file removed, audited « Supprimé »; no logo → no-op, no audit")
    void delete() {
        when(repository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store(storeId + ".png")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.delete(storeId, actorId).logoPath()).isNull();
        verify(storage).delete(SCHEMA, storeId + ".png");
        ArgumentCaptor<StoreUpdatedEvent> event = ArgumentCaptor.forClass(StoreUpdatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().after()).isEqualTo(Map.of("logo", "Supprimé"));

        reset(repository, storage, events);
        when(repository.findByIdForUpdate(storeId)).thenReturn(Optional.of(store(null)));
        service.delete(storeId, actorId);
        verify(repository, never()).save(any());
        verifyNoInteractions(storage, events);
    }

    @Test
    @DisplayName("read: STORE_LOGO_NOT_FOUND without a logo or when the file is gone")
    void read() throws Exception {
        when(repository.findById(storeId)).thenReturn(Optional.of(store(null)));
        assertThatThrownBy(() -> service.read(storeId))
                .satisfies(e -> assertThat(code(e)).isEqualTo("STORE_LOGO_NOT_FOUND"));

        when(repository.findById(storeId)).thenReturn(Optional.of(store(storeId + ".png")));
        when(storage.read(SCHEMA, storeId + ".png")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(storeId))
                .satisfies(e -> assertThat(code(e)).isEqualTo("STORE_LOGO_NOT_FOUND"));

        when(storage.read(SCHEMA, storeId + ".png")).thenReturn(Optional.of(new StoredLogo(new byte[]{5}, "image/png")));
        assertThat(service.read(storeId).content()).containsExactly(5);
    }
}
