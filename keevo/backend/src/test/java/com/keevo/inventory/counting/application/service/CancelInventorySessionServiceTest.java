package com.keevo.inventory.counting.application.service;

import com.keevo.inventory.counting.domain.event.InventorySessionCancelledEvent;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.CancelInventorySessionCommand;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelInventorySessionService")
class CancelInventorySessionServiceTest {

    @Mock private InventorySessionRepository sessionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CancelInventorySessionService service;

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID EMPLOYEE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CancelInventorySessionService(sessionRepository, eventPublisher);
    }

    private InventorySession createActiveSession(UUID startedBy) {
        return InventorySession.create(UUID.randomUUID(), InventoryScope.FULL, null, startedBy);
    }

    @Test
    void cancel_whenInProgress_shouldSetCancelled() {
        var session = createActiveSession(OWNER_ID);
        when(sessionRepository.findById(any())).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new CancelInventorySessionCommand(SESSION_ID, OWNER_ID, "OWNER"));

        assertThat(session.getStatus()).isEqualTo(InventorySessionStatus.CANCELLED);
        verify(sessionRepository).save(session);
    }

    @Test
    void cancel_whenNotFound_shouldThrow() {
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(
                new CancelInventorySessionCommand(SESSION_ID, OWNER_ID, "OWNER")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo("INVENTORY_SESSION_NOT_FOUND"));
    }

    @Test
    void cancel_whenAlreadyCancelled_shouldThrow() {
        var session = createActiveSession(OWNER_ID);
        session.cancel(OWNER_ID);
        when(sessionRepository.findById(any())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.execute(
                new CancelInventorySessionCommand(SESSION_ID, OWNER_ID, "OWNER")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo("INVENTORY_SESSION_NOT_IN_PROGRESS"));
    }

    @Test
    void cancel_employee_canCancelOwnSession() {
        var session = createActiveSession(EMPLOYEE_ID);
        when(sessionRepository.findById(any())).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new CancelInventorySessionCommand(SESSION_ID, EMPLOYEE_ID, "EMPLOYEE"));

        assertThat(session.getStatus()).isEqualTo(InventorySessionStatus.CANCELLED);
    }

    @Test
    void cancel_employee_cannotCancelOthersSession() {
        var session = createActiveSession(OWNER_ID); // started by someone else
        when(sessionRepository.findById(any())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.execute(
                new CancelInventorySessionCommand(SESSION_ID, EMPLOYEE_ID, "EMPLOYEE")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getDomainCode())
                        .isEqualTo("FORBIDDEN"));
    }

    @Test
    void cancel_owner_canCancelAnySession() {
        var session = createActiveSession(EMPLOYEE_ID); // started by employee
        when(sessionRepository.findById(any())).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new CancelInventorySessionCommand(SESSION_ID, OWNER_ID, "OWNER"));

        assertThat(session.getStatus()).isEqualTo(InventorySessionStatus.CANCELLED);
    }

    @Test
    void cancel_shouldPublishEvent() {
        var session = createActiveSession(OWNER_ID);
        when(sessionRepository.findById(any())).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new CancelInventorySessionCommand(SESSION_ID, OWNER_ID, "OWNER"));

        ArgumentCaptor<InventorySessionCancelledEvent> captor =
                ArgumentCaptor.forClass(InventorySessionCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(OWNER_ID);
    }
}
