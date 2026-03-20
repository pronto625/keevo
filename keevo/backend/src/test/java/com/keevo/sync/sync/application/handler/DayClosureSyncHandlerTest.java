package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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

    @Test
    void handle_validClosure_delegatesToCloseDayService() {
        var op = new SyncOperation("op-1", "CREATE_DAY_CLOSURE", UUID.randomUUID().toString(),
                Map.of("storeId", UUID.randomUUID().toString()),
                Instant.now());

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

    @Test
    void supportedTypes_containsCreateDayClosure() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_DAY_CLOSURE");
    }
}
