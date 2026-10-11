package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DayClosureSyncHandlerTest {

    @Mock private CloseDayUseCase closeDayUseCase;

    private DayClosureSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new DayClosureSyncHandler(closeDayUseCase);
    }

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    @Test
    void handle_employeeClosingAnotherStore_isForbidden() {
        SyncTestAuth.employee(UUID.randomUUID());
        var op = new SyncOperation("op-1", "CREATE_DAY_CLOSURE", UUID.randomUUID().toString(),
                Map.of("storeId", UUID.randomUUID().toString()),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        org.mockito.Mockito.verifyNoInteractions(closeDayUseCase);
    }

    @Test
    void handle_validClosure_delegatesToCloseDayService() {
        var op = new SyncOperation("op-1", "CREATE_DAY_CLOSURE", UUID.randomUUID().toString(),
                Map.of("storeId", UUID.randomUUID().toString()),
                Instant.now());
        when(closeDayUseCase.closeDay(any())).thenAnswer(inv -> closureFrom(inv.getArgument(0)));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(closeDayUseCase).closeDay(any());
    }

    @Test
    void handle_alreadyClosed_returnsDuplicate() {
        var op = new SyncOperation("op-1", "CREATE_DAY_CLOSURE", UUID.randomUUID().toString(),
                Map.of("storeId", UUID.randomUUID().toString()),
                Instant.now());

        doThrow(new DomainException(ErrorCode.DAY_ALREADY_CLOSED))
                .when(closeDayUseCase).closeDay(any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.DUPLICATE);
    }

    // ── Offline closure keeps the device's id and the day it was made ──────

    private static DayClosure closureFrom(CloseDayCommand cmd) {
        return new DayClosure(cmd.closureId(), cmd.storeId(), cmd.actorId(),
                cmd.closedAt() != null ? cmd.closedAt() : Instant.now(), null, false, cmd.tenantId());
    }

    private CloseDayCommand capturedCommand() {
        var captor = ArgumentCaptor.forClass(CloseDayCommand.class);
        verify(closeDayUseCase).closeDay(captor.capture());
        return captor.getValue();
    }

    @Test
    void handle_offlineClosure_passesClientIdAndClosedAt() {
        var closureId = UUID.randomUUID();
        var closedAt = Instant.parse("2026-09-28T20:00:00Z");
        var op = new SyncOperation("op-1", "CREATE_DAY_CLOSURE", closureId.toString(),
                Map.of("storeId", UUID.randomUUID().toString(), "id", closureId.toString(),
                        "closedAt", "2026-09-28T20:00:00.000Z"),
                Instant.parse("2026-09-29T09:00:00Z"));
        when(closeDayUseCase.closeDay(any())).thenAnswer(inv -> closureFrom(inv.getArgument(0)));

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        var cmd = capturedCommand();
        assertThat(cmd.closureId()).isEqualTo(closureId);
        assertThat(cmd.closedAt()).isEqualTo(closedAt);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(closureId.toString());
    }

    @Test
    void handle_legacyPayloadWithoutClosedAt_usesClientTimestampAndEntityId() {
        var closureId = UUID.randomUUID();
        var queuedAt = Instant.parse("2026-09-28T19:30:00Z");
        var op = new SyncOperation("op-1", "CREATE_DAY_CLOSURE", closureId.toString(),
                Map.of("storeId", UUID.randomUUID().toString()), queuedAt);
        when(closeDayUseCase.closeDay(any())).thenAnswer(inv -> closureFrom(inv.getArgument(0)));

        handler.handle(op, ACTOR_ID, TENANT_ID);

        var cmd = capturedCommand();
        assertThat(cmd.closureId()).isEqualTo(closureId);
        assertThat(cmd.closedAt()).isEqualTo(queuedAt);
    }

    @Test
    void supportedTypes_containsCreateDayClosure() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_DAY_CLOSURE");
    }
}
