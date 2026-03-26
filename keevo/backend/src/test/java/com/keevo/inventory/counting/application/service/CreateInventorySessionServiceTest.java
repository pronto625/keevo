package com.keevo.inventory.counting.application.service;

import com.keevo.catalog.category.domain.model.Category;
import com.keevo.catalog.category.domain.port.out.CategoryRepository;
import com.keevo.inventory.counting.domain.event.InventorySessionCreatedEvent;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.model.StoreType;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateInventorySessionService")
class CreateInventorySessionServiceTest {

    @Mock private InventorySessionRepository sessionRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CreateInventorySessionService service;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final Store STORE = new Store(STORE_ID, "Boutique Test", StoreType.STORE, null, null, true, Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        service = new CreateInventorySessionService(sessionRepository, storeRepository, categoryRepository, eventPublisher);
    }

    @Test
    void create_shouldPersistSession() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        var result = service.execute(cmd);

        assertThat(result.getStoreId()).isEqualTo(STORE_ID);
        assertThat(result.getScope()).isEqualTo(InventoryScope.FULL);
        assertThat(result.getStatus()).isEqualTo(InventorySessionStatus.IN_PROGRESS);
        verify(sessionRepository).save(any(InventorySession.class));
    }

    @Test
    void create_whenActiveSessionExists_shouldThrowConflict() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        var activeSession = InventorySession.create(STORE_ID, InventoryScope.FULL, null, UUID.randomUUID());
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.of(activeSession));

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo("INVENTORY_SESSION_ALREADY_ACTIVE"));
    }

    @Test
    void create_whenStoreNotFound_shouldThrow() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.empty());

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo("INVENTORY_STORE_NOT_FOUND"));
    }

    @Test
    void create_partialScope_withInvalidCategories_shouldThrow() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.empty());
        UUID invalidCatId = UUID.randomUUID();
        when(categoryRepository.findById(invalidCatId)).thenReturn(Optional.empty());

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.PARTIAL, List.of(invalidCatId), ACTOR_ID);
        assertThatThrownBy(() -> service.execute(cmd))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo("INVENTORY_INVALID_CATEGORIES"));
    }

    @Test
    void create_fullScope_shouldIgnoreCategoryIds() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.FULL, List.of(UUID.randomUUID()), ACTOR_ID);
        var result = service.execute(cmd);

        assertThat(result.getCategoryIds()).isNull();
        verify(categoryRepository, never()).findById(any());
    }

    @Test
    void create_shouldPublishEvent() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        service.execute(cmd);

        ArgumentCaptor<InventorySessionCreatedEvent> captor = ArgumentCaptor.forClass(InventorySessionCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().storeId()).isEqualTo(STORE_ID);
        assertThat(captor.getValue().actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    void create_partialScope_withValidCategories_shouldSucceed() {
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID catId = UUID.randomUUID();
        when(categoryRepository.findById(catId)).thenReturn(Optional.of(
                new Category(catId, "Test", null, true, false, Instant.now(), Instant.now())));

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.PARTIAL, List.of(catId), ACTOR_ID);
        var result = service.execute(cmd);

        assertThat(result.getScope()).isEqualTo(InventoryScope.PARTIAL);
        assertThat(result.getCategoryIds()).containsExactly(catId);
    }
}
