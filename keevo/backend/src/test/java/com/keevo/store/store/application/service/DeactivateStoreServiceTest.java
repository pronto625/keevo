package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
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

    @Test
    @DisplayName("19.12 — deactivation keeps every Bref field and the logo")
    void deactivate_keepsBrefFields() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(new Store(storeId, "Boutique", StoreType.STORE, "Yaoundé", "+237690000001", true,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                com.keevo.store.api.StoreCategory.JEWELRY, "Bijoux", storeId + "-abc.png", "M0123", "RC1", "CI")));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store result = deactivateStoreService.execute(new DeactivateStoreCommand(storeId, actorId));

        assertThat(result.isActive()).isFalse();
        assertThat(result.name()).isEqualTo("Boutique");
        assertThat(result.address()).isEqualTo("Yaoundé");
        assertThat(result.phone()).isEqualTo("+237690000001");
        assertThat(result.category()).isEqualTo(com.keevo.store.api.StoreCategory.JEWELRY);
        assertThat(result.description()).isEqualTo("Bijoux");
        assertThat(result.logoPath()).isEqualTo(storeId + "-abc.png");
        assertThat(result.niu()).isEqualTo("M0123");
        assertThat(result.rccm()).isEqualTo("RC1");
        assertThat(result.country()).isEqualTo("CI");
    }
}
