package com.keevo.inventory.counting.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.catalog.api.CategoryQueryPort;
import com.keevo.catalog.api.CategoryView;
import com.keevo.shared.event.catalog.counting.InventorySessionCreatedEvent;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.CreateInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreType;
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

    // 12.2d: the store / identity / subscription API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12d() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private InventorySessionRepository sessionRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private CategoryQueryPort categoryQuery;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CreateInventorySessionService service;

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final Store STORE = new Store(STORE_ID, "Boutique Test", StoreType.STORE, null, null, true, Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        service = new CreateInventorySessionService(sessionRepository, StoreApiTestSupport.queryPort(storeRepository), categoryQuery, eventPublisher);
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
        when(categoryQuery.findById(TenantContext.getCurrentTenant(), invalidCatId)).thenReturn(Optional.empty());

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
        verify(categoryQuery, never()).findById(any(), any());
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
        when(categoryQuery.findById(TenantContext.getCurrentTenant(), catId)).thenReturn(Optional.of(
                new CategoryView(catId, "Test", null, true, false, Instant.now(), Instant.now())));

        var cmd = new CreateInventorySessionCommand(STORE_ID, InventoryScope.PARTIAL, List.of(catId), ACTOR_ID);
        var result = service.execute(cmd);

        assertThat(result.getScope()).isEqualTo(InventoryScope.PARTIAL);
        assertThat(result.getCategoryIds()).containsExactly(catId);
    }

    // ── v1s-13-8: client-supplied session id ─────────────────────

    @Test
    void create_withClientSessionId_replay_returnsExisting_noChecksNoSaveNoEvent() {
        UUID sessionId = UUID.randomUUID();
        var existing = InventorySession.create(sessionId, STORE_ID, InventoryScope.FULL, null, ACTOR_ID);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(existing));

        var result = service.execute(new CreateInventorySessionCommand(
                sessionId, STORE_ID, InventoryScope.FULL, null, ACTOR_ID));

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(storeRepository, eventPublisher);
        verify(sessionRepository, never()).findActiveByStoreId(any());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void create_withClientSessionId_new_isSavedWithClientId_andEventPublished() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.execute(new CreateInventorySessionCommand(
                sessionId, STORE_ID, InventoryScope.FULL, null, ACTOR_ID));

        assertThat(result.getId()).isEqualTo(sessionId);
        ArgumentCaptor<InventorySessionCreatedEvent> captor = ArgumentCaptor.forClass(InventorySessionCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
    }

    @Test
    void create_withClientSessionId_otherActiveSession_throwsAlreadyActive() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());
        when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(STORE));
        when(sessionRepository.findActiveByStoreId(STORE_ID)).thenReturn(Optional.of(
                InventorySession.create(STORE_ID, InventoryScope.FULL, null, UUID.randomUUID())));

        assertThatThrownBy(() -> service.execute(new CreateInventorySessionCommand(
                sessionId, STORE_ID, InventoryScope.FULL, null, ACTOR_ID)))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo("INVENTORY_SESSION_ALREADY_ACTIVE"));
        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void create_withClientSessionId_replayFromAnotherStore_throwsForbidden() {
        UUID sessionId = UUID.randomUUID();
        var existing = InventorySession.create(sessionId, UUID.randomUUID(), InventoryScope.FULL, null, ACTOR_ID);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute(new CreateInventorySessionCommand(
                sessionId, STORE_ID, InventoryScope.FULL, null, ACTOR_ID)))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode()).isEqualTo("FORBIDDEN"));
        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
