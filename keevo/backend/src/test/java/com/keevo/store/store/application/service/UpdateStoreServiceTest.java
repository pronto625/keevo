package com.keevo.store.store.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.shared.event.catalog.store.StoreUpdatedEvent;
import com.keevo.store.api.StoreCategory;
import com.keevo.store.api.StoreProfilePatch;
import com.keevo.store.api.StoreType;
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

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
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

    // ── Story 19.12 — Bref fields ─────────────────────────────────────────────

    private Store brefStore() {
        return new Store(storeId, "Boutique", StoreType.STORE, "Yaoundé", "+237690000001", true,
                Instant.now(), Instant.now(), StoreCategory.FOOD, "Épicerie", "1234.png", "M0123", "RC1", "CM");
    }

    @Test
    @DisplayName("19.12 — a V1 command (name/address/phone only) keeps every Bref field and the logo")
    void v1CommandKeepsBrefFields() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(brefStore()));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store result = updateStoreService.execute(new UpdateStoreCommand(storeId, "Nouveau", "Douala", null, actorId));

        assertThat(result.name()).isEqualTo("Nouveau");
        assertThat(result.address()).isEqualTo("Douala");
        assertThat(result.phone()).isNull(); // unchanged 3.1 semantics: null clears the phone
        assertThat(result.category()).isEqualTo(StoreCategory.FOOD);
        assertThat(result.description()).isEqualTo("Épicerie");
        assertThat(result.niu()).isEqualTo("M0123");
        assertThat(result.rccm()).isEqualTo("RC1");
        assertThat(result.country()).isEqualTo("CM");
        assertThat(result.logoPath()).isEqualTo("1234.png");
        ArgumentCaptor<StoreUpdatedEvent> event = ArgumentCaptor.forClass(StoreUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().before()).isEqualTo(Map.of("name", "Boutique", "address", "Yaoundé",
                "phone", "+237690000001"));
        assertThat(event.getValue().after()).isEqualTo(Map.of("name", "Nouveau", "address", "Douala"));
    }

    @Test
    @DisplayName("19.12 — Bref fields set / cleared; the event carries before → after in French")
    void brefFieldsAudited() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(brefStore()));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store result = updateStoreService.execute(new UpdateStoreCommand(storeId, "Boutique", "Yaoundé",
                "+237690000001", actorId, new StoreProfilePatch("real_estate", "", " M999 ", null, "ci")));

        assertThat(result.category()).isEqualTo(StoreCategory.REAL_ESTATE);
        assertThat(result.description()).isNull();
        assertThat(result.niu()).isEqualTo("M999");
        assertThat(result.rccm()).isEqualTo("RC1");
        assertThat(result.country()).isEqualTo("CI");
        ArgumentCaptor<StoreUpdatedEvent> event = ArgumentCaptor.forClass(StoreUpdatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().before()).isEqualTo(Map.of("category", "Alimentation",
                "description", "Épicerie", "niu", "M0123", "country", "CM"));
        assertThat(event.getValue().after()).isEqualTo(Map.of("category", "Immobilier", "niu", "M999",
                "country", "CI"));
    }

    @Test
    @DisplayName("19.12 — an invalid Bref field refuses the whole update (nothing saved, no event)")
    void invalidBrefFieldSavesNothing() {
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(brefStore()));

        assertThatThrownBy(() -> updateStoreService.execute(new UpdateStoreCommand(storeId, "Boutique", null, null,
                actorId, new StoreProfilePatch("Alimentation", null, null, null, null))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode())
                .isEqualTo("VALIDATION_ERROR");
        verify(storeRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
