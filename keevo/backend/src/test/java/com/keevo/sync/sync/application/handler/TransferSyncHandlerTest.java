package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
import com.keevo.catalog.stock.domain.port.in.TransferStockUseCase;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TransferSyncHandlerTest — Story v1s-12-8 AC4/AC6.
 */
@ExtendWith(MockitoExtension.class)
class TransferSyncHandlerTest {

    @Mock private TransferStockUseCase transferStockUseCase;

    private TransferSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new TransferSyncHandler(transferStockUseCase);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsOwner() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ACTOR_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    private void authenticateAsEmployee() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ACTOR_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }

    private SyncOperation buildOp() {
        return new SyncOperation("op-1", "STOCK_TRANSFER", UUID.randomUUID().toString(),
                Map.of("sourceStoreId", UUID.randomUUID().toString(),
                        "destinationStoreId", UUID.randomUUID().toString(),
                        "productId", UUID.randomUUID().toString(),
                        "quantity", 10),
                Instant.now());
    }

    @Test
    void handle_validTransfer_owner_delegates() {
        authenticateAsOwner();
        var transferId = UUID.randomUUID();
        var mockTransfer = mock(StockTransfer.class);
        when(mockTransfer.getId()).thenReturn(transferId);
        when(transferStockUseCase.execute(any())).thenReturn(mockTransfer);

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(transferStockUseCase).execute(any());
    }

    @Test
    void handle_validTransfer_employee_delegates() {
        // Story 12.6's original OWNER-only governance was superseded: employees
        // are the ones performing this action in practice (any store).
        authenticateAsEmployee();
        var transferId = UUID.randomUUID();
        var mockTransfer = mock(StockTransfer.class);
        when(mockTransfer.getId()).thenReturn(transferId);
        when(transferStockUseCase.execute(any())).thenReturn(mockTransfer);

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(transferStockUseCase).execute(any());
    }

    @Test
    void handle_invalidTransfer_returnsRejected() {
        authenticateAsOwner();
        var op = buildOp();

        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK))
                .when(transferStockUseCase).execute(any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void supportedTypes_containsStockTransfer() {
        assertThat(handler.supportedTypes()).containsExactly("STOCK_TRANSFER");
    }

    @Test
    void apply_propagates_entityId_as_clientId() {
        // Offline-first fix: operation.entityId() (the client-generated local id) must be
        // forwarded as TransferStockCommand.clientId() so ExecuteTransferService preserves it
        // instead of minting a new server-side UUID — prevents a phantom duplicate on pull.
        authenticateAsOwner();
        var op = buildOp();
        var mockTransfer = mock(StockTransfer.class);
        when(mockTransfer.getId()).thenReturn(UUID.randomUUID());
        when(transferStockUseCase.execute(any())).thenReturn(mockTransfer);

        handler.handle(op, ACTOR_ID, TENANT_ID);

        ArgumentCaptor<TransferStockCommand> captor = ArgumentCaptor.forClass(TransferStockCommand.class);
        verify(transferStockUseCase).execute(captor.capture());
        assertThat(captor.getValue().clientId()).isEqualTo(UUID.fromString(op.entityId()));
    }
}
