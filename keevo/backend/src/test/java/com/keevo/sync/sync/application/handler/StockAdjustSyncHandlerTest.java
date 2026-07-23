package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.stock.application.usecase.AdjustStockUseCase;
import com.keevo.catalog.stock.application.usecase.RecordStockEntryUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * StockAdjustSyncHandlerTest — Story v1s-12-8 AC3/AC6.
 */
@ExtendWith(MockitoExtension.class)
class StockAdjustSyncHandlerTest {

    @Mock private AdjustStockUseCase adjustStockUseCase;
    @Mock private RecordStockEntryUseCase recordStockEntryUseCase;

    private StockAdjustSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new StockAdjustSyncHandler(adjustStockUseCase, recordStockEntryUseCase);
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

    private void authenticateAsEmployee(UUID storeId) {
        var auth = new UsernamePasswordAuthenticationToken(
                ACTOR_ID, null,
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        auth.setDetails(new AuthDetails("Loïc", storeId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private SyncOperation stockAdjustOp(UUID storeId) {
        return new SyncOperation("op-1", "STOCK_ADJUST", UUID.randomUUID().toString(),
                Map.of("productId", UUID.randomUUID().toString(),
                        "storeId", storeId.toString(),
                        "quantity", 50),
                Instant.now());
    }

    private SyncOperation stockEntryOp(UUID storeId) {
        return new SyncOperation("op-2", "RECORD_STOCK_ENTRY", UUID.randomUUID().toString(),
                Map.of("productId", UUID.randomUUID().toString(),
                        "storeId", storeId.toString(),
                        "quantity", 20),
                Instant.now());
    }

    @Test
    void handle_validAdjust_owner_delegates() {
        authenticateAsOwner();
        var result = handler.handle(stockAdjustOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(adjustStockUseCase).execute(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void handle_validAdjust_employeeSameStore_applied() {
        authenticateAsEmployee(STORE_ID);
        var result = handler.handle(stockAdjustOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(adjustStockUseCase).execute(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void handle_validAdjust_employeeCrossStore_rejectedForbidden() {
        UUID otherStoreId = UUID.randomUUID();
        authenticateAsEmployee(otherStoreId);
        var result = handler.handle(stockAdjustOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(adjustStockUseCase, never()).execute(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void handle_recordStockEntry_employeeSameStore_applied() {
        authenticateAsEmployee(STORE_ID);
        var result = handler.handle(stockEntryOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(recordStockEntryUseCase).execute(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void handle_recordStockEntry_employeeCrossStore_rejectedForbidden() {
        UUID otherStoreId = UUID.randomUUID();
        authenticateAsEmployee(otherStoreId);
        var result = handler.handle(stockEntryOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(recordStockEntryUseCase, never()).execute(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void handle_negativeStock_returnsConflict() {
        authenticateAsOwner();
        var op = stockAdjustOp(STORE_ID);

        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK))
                .when(adjustStockUseCase).execute(any(), any(), any(), anyInt(), any(), any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
    }

    @Test
    void supportedTypes_containsStockAdjustAndEntry() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("STOCK_ADJUST", "RECORD_STOCK_ENTRY");
    }
}
