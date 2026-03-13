package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.in.UpdateStoreCommand;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UpdateStoreServiceTest — TDD unit tests (Story 3.1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateStoreService")
class UpdateStoreServiceTest {

    @Mock StoreRepository storeRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks UpdateStoreService updateStoreService;

    UUID storeId;
    UUID actorId;
    Store existingStore;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        existingStore = new Store(storeId, "Old Name", StoreType.STORE,
                null, null, true, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("execute() should update name, address and phone")
    void execute_shouldUpdateNameAddressPhone() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existingStore));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateStoreCommand cmd = new UpdateStoreCommand(storeId, "New Name", "Rue Test", "+237690000001", actorId);
        Store result = updateStoreService.execute(cmd);

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.address()).isEqualTo("Rue Test");
        assertThat(result.phone()).isEqualTo("+237690000001");
        assertThat(result.id()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("execute() should throw STORE_NOT_FOUND when invalid ID")
    void execute_shouldThrow_STORE_NOT_FOUND_whenInvalidId() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        UpdateStoreCommand cmd = new UpdateStoreCommand(storeId, "Name", null, null, actorId);
        assertThatThrownBy(() -> updateStoreService.execute(cmd))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.STORE_NOT_FOUND.name());
    }

    @Test
    @DisplayName("execute() should publish StoreUpdatedEvent")
    void execute_shouldPublishStoreUpdatedEvent() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(existingStore));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        updateStoreService.execute(new UpdateStoreCommand(storeId, "Updated", null, null, actorId));

        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("execute() should NOT change store type")
    void execute_shouldNotChangeType() {
        Store warehouseStore = new Store(storeId, "Depot", StoreType.WAREHOUSE,
                null, null, true, Instant.now(), Instant.now());
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(warehouseStore));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store result = updateStoreService.execute(new UpdateStoreCommand(storeId, "Updated Depot", null, null, actorId));

        assertThat(result.type()).isEqualTo(StoreType.WAREHOUSE);
    }
}
