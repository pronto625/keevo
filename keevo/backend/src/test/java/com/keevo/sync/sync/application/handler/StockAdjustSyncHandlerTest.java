package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.api.StockCommandPort;
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

    @Mock private StockCommandPort stockCommand;

    private StockAdjustSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new StockAdjustSyncHandler(stockCommand);
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
        verify(stockCommand).adjustTo(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void handle_validAdjust_employeeSameStore_applied() {
        authenticateAsEmployee(STORE_ID);
        var result = handler.handle(stockAdjustOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(stockCommand).adjustTo(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void handle_validAdjust_employeeCrossStore_rejectedForbidden() {
        UUID otherStoreId = UUID.randomUUID();
        authenticateAsEmployee(otherStoreId);
        var result = handler.handle(stockAdjustOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(stockCommand, never()).adjustTo(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void handle_recordStockEntry_employeeSameStore_applied() {
        authenticateAsEmployee(STORE_ID);
        var result = handler.handle(stockEntryOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(stockCommand).recordEntry(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void handle_recordStockEntry_employeeCrossStore_rejectedForbidden() {
        UUID otherStoreId = UUID.randomUUID();
        authenticateAsEmployee(otherStoreId);
        var result = handler.handle(stockEntryOp(STORE_ID), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(stockCommand, never()).recordEntry(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void handle_negativeStock_returnsConflict() {
        authenticateAsOwner();
        var op = stockAdjustOp(STORE_ID);

        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK))
                .when(stockCommand).adjustTo(any(), any(), any(), anyInt(), any(), any(), any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
    }

    // ── The device's movement id is kept (no duplicate row after pull) ──────

    @Test
    void handle_adjust_passesEntityIdAsMovementId() {
        authenticateAsOwner();
        var op = stockAdjustOp(STORE_ID);

        handler.handle(op, ACTOR_ID, TENANT_ID);

        verify(stockCommand).adjustTo(any(), any(), any(), anyInt(), any(), any(),
                eq(UUID.fromString(op.entityId())));
    }

    @Test
    void handle_stockEntry_passesEntityIdAsMovementId() {
        authenticateAsOwner();
        var op = stockEntryOp(STORE_ID);

        handler.handle(op, ACTOR_ID, TENANT_ID);

        verify(stockCommand).recordEntry(any(), any(), any(), anyInt(), any(), any(),
                eq(UUID.fromString(op.entityId())));
    }

    // Some callers queue the product id as entityId: it is not a movement id.
    @Test
    void handle_entityIdIsTheProductId_serverGeneratesMovementId() {
        authenticateAsOwner();
        var productId = UUID.randomUUID().toString();
        var op = new SyncOperation("op-3", "RECORD_STOCK_ENTRY", productId,
                Map.of("productId", productId, "storeId", STORE_ID.toString(), "quantity", 5),
                Instant.now());

        handler.handle(op, ACTOR_ID, TENANT_ID);

        verify(stockCommand).recordEntry(any(), any(), any(), anyInt(), any(), any(), isNull());
    }

    @Test
    void supportedTypes_containsStockAdjustAndEntry() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("STOCK_ADJUST", "RECORD_STOCK_ENTRY");
    }

    // ── v1s-13-8: strict integral quantity (no silent truncation) ──────────

    private SyncOperation adjustWithQuantity(Object quantity) {
        return new SyncOperation("op-q", "STOCK_ADJUST", UUID.randomUUID().toString(),
                Map.of("productId", UUID.randomUUID().toString(),
                        "storeId", STORE_ID.toString(),
                        "quantity", quantity),
                Instant.now());
    }

    @Test
    void handle_fractionalQuantity_isRejected_useCaseNeverCalled() {
        authenticateAsOwner();
        var result = handler.handle(adjustWithQuantity(1.5), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(stockCommand);
    }

    @Test
    void handle_stringQuantity_isRejected_useCaseNeverCalled() {
        authenticateAsOwner();
        var result = handler.handle(adjustWithQuantity("3"), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(stockCommand);
    }

    @Test
    void handle_quantityBeyondIntRange_isRejected_useCaseNeverCalled() {
        authenticateAsOwner();
        var result = handler.handle(adjustWithQuantity((long) Integer.MAX_VALUE + 1), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(stockCommand);
    }

    @Test
    void handle_wholeDoubleQuantity_isAppliedAsInt() {
        authenticateAsOwner();
        var result = handler.handle(adjustWithQuantity(5.0), ACTOR_ID, TENANT_ID);
        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(stockCommand).adjustTo(any(), any(), eq(STORE_ID), eq(5), eq(ACTOR_ID), any(), any());
    }
}
