package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.event.InventoryCountSavedEvent;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.SaveInventoryCountCommand;
import com.keevo.inventory.counting.domain.port.out.InventoryCountRepository;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("SaveInventoryCountService — save/update physical count")
class SaveInventoryCountServiceTest {

    @Mock private InventorySessionRepository sessionRepository;
    @Mock private InventoryCountRepository countRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SaveInventoryCountService service;

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SaveInventoryCountService(sessionRepository, countRepository, eventPublisher);
        TenantContext.setCurrentTenant("tenant_test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SaveInventoryCountCommand buildCommand() {
        return new SaveInventoryCountCommand(SESSION_ID, PRODUCT_ID, null,
                "Savon bio", null, 50, 47, ACTOR_ID);
    }

    @Test
    void execute_shouldThrowWhenSessionNotFound() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(buildCommand()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_FOUND);
    }

    @Test
    void execute_shouldThrowWhenSessionNotInProgress() {
        var session = new InventorySession(SESSION_ID, STORE_ID, InventoryScope.FULL,
                null, InventorySessionStatus.VALIDATED, UUID.randomUUID(), Instant.now(),
                null, null, Instant.now(), Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.execute(buildCommand()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS);
    }

    @Test
    void execute_shouldUpsertAndPublishEvent() {
        var session = InventorySession.create(STORE_ID, InventoryScope.FULL, null, UUID.randomUUID());
        // Override session ID: use the constructor form
        var sessionWithId = new InventorySession(SESSION_ID, STORE_ID, InventoryScope.FULL,
                null, InventorySessionStatus.IN_PROGRESS, UUID.randomUUID(), Instant.now(),
                null, null, null, Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionWithId));
        when(countRepository.upsert(any(InventoryCount.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryCount result = service.execute(buildCommand());

        assertThat(result.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(result.getPhysical()).isEqualTo(47);
        assertThat(result.getTheoretical()).isEqualTo(50);

        // Verify event published
        ArgumentCaptor<InventoryCountSavedEvent> captor = ArgumentCaptor.forClass(InventoryCountSavedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        InventoryCountSavedEvent event = captor.getValue();
        assertThat(event.productId()).isEqualTo(PRODUCT_ID);
        assertThat(event.ecart()).isEqualTo(-3);
        assertThat(event.tenantId()).isEqualTo("tenant_test");
    }

    @Test
    void execute_shouldPassCountToRepository() {
        var sessionWithId = new InventorySession(SESSION_ID, STORE_ID, InventoryScope.FULL,
                null, InventorySessionStatus.IN_PROGRESS, UUID.randomUUID(), Instant.now(),
                null, null, null, Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionWithId));
        when(countRepository.upsert(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(buildCommand());

        ArgumentCaptor<InventoryCount> captor = ArgumentCaptor.forClass(InventoryCount.class);
        verify(countRepository).upsert(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(captor.getValue().getProductName()).isEqualTo("Savon bio");
    }
}
