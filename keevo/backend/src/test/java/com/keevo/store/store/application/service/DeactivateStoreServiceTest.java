package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.in.DeactivateStoreCommand;
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
 * DeactivateStoreServiceTest — TDD unit tests (Story 3.1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeactivateStoreService")
class DeactivateStoreServiceTest {

    @Mock StoreRepository storeRepository;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks DeactivateStoreService deactivateStoreService;

    UUID storeId;
    UUID actorId;
    Store activeStore;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        activeStore = new Store(storeId, "Active Store", StoreType.STORE,
                null, null, true, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("execute() should set isActive=false")
    void execute_shouldSetIsActiveFalse() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(activeStore));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store result = deactivateStoreService.execute(new DeactivateStoreCommand(storeId, actorId));

        assertThat(result.isActive()).isFalse();
        assertThat(result.id()).isEqualTo(storeId);
    }

    @Test
    @DisplayName("execute() should throw STORE_NOT_FOUND when invalid ID")
    void execute_shouldThrow_STORE_NOT_FOUND_whenInvalidId() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deactivateStoreService.execute(new DeactivateStoreCommand(storeId, actorId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo(ErrorCode.STORE_NOT_FOUND.name());
    }

    @Test
    @DisplayName("execute() should publish StoreDeactivatedEvent")
    void execute_shouldPublishStoreDeactivatedEvent() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(activeStore));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        deactivateStoreService.execute(new DeactivateStoreCommand(storeId, actorId));

        verify(eventPublisher).publishEvent(any(Object.class));
    }
}
