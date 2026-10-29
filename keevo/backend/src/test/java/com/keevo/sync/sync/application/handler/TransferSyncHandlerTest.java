package com.keevo.sync.sync.application.handler;

import com.keevo.inventory.api.TransferCommandPort;
import com.keevo.inventory.api.TransferInput;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.AuthDetails;
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
 * TransferSyncHandlerTest — Story v1s-12-8 AC4/AC6, v1s-12-11 (employee own-store scope).
 */
@ExtendWith(MockitoExtension.class)
class TransferSyncHandlerTest {

    @Mock private TransferCommandPort transferCommand;

    private TransferSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new TransferSyncHandler(transferCommand);
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

    private SyncOperation buildOp() {
        return buildOp(UUID.randomUUID().toString());
    }

    private SyncOperation buildOp(String sourceStoreId) {
        return new SyncOperation("op-1", "STOCK_TRANSFER", UUID.randomUUID().toString(),
                Map.of("sourceStoreId", sourceStoreId,
                        "destinationStoreId", UUID.randomUUID().toString(),
                        "productId", UUID.randomUUID().toString(),
                        "quantity", 10),
                Instant.now());
    }

    @Test
    void handle_validTransfer_owner_delegates() {
        // OWNER is never scoped: any source store of the tenant is APPLIED.
        authenticateAsOwner();
        var transferId = UUID.randomUUID();
        when(transferCommand.transfer(any())).thenReturn(transferId);

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(transferCommand).transfer(any());
    }

    // ── Story v1s-12-11 — EMPLOYEE transfers only from their assigned store ──

    @Test
    void handle_employeeOwnStore_applied() {
        UUID storeA = UUID.randomUUID();
        authenticateAsEmployee(storeA);
        when(transferCommand.transfer(any())).thenReturn(UUID.randomUUID());

        var result = handler.handle(buildOp(storeA.toString()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(transferCommand).transfer(any());
    }

    @Test
    void handle_employeeOtherStore_rejectedForbidden() {
        authenticateAsEmployee(UUID.randomUUID());

        var result = handler.handle(buildOp(UUID.randomUUID().toString()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(transferCommand, never()).transfer(any());
    }

    @Test
    void handle_employeeWithoutStoreClaim_rejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        ACTOR_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(transferCommand, never()).transfer(any());
    }

    @Test
    void handle_malformedSourceStoreId_rejected() {
        authenticateAsEmployee(UUID.randomUUID());

        var result = handler.handle(buildOp("not-a-uuid"), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verify(transferCommand, never()).transfer(any());
    }

    @Test
    void handle_invalidTransfer_returnsRejected() {
        authenticateAsOwner();
        var op = buildOp();

        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK))
                .when(transferCommand).transfer(any());

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
        // forwarded as TransferInput.clientId() so ExecuteTransferService preserves it
        // instead of minting a new server-side UUID — prevents a phantom duplicate on pull.
        authenticateAsOwner();
        var op = buildOp();
        when(transferCommand.transfer(any())).thenReturn(UUID.randomUUID());

        handler.handle(op, ACTOR_ID, TENANT_ID);

        ArgumentCaptor<TransferInput> captor = ArgumentCaptor.forClass(TransferInput.class);
        verify(transferCommand).transfer(captor.capture());
        assertThat(captor.getValue().clientId()).isEqualTo(UUID.fromString(op.entityId()));
    }

    // ── v1s-15-9: integral guard ──────────────────────────────────────

    private SyncOperation transferWithQuantity(Object quantity) {
        return new SyncOperation("op-int", "STOCK_TRANSFER", UUID.randomUUID().toString(),
                Map.of("sourceStoreId", UUID.randomUUID().toString(),
                        "destinationStoreId", UUID.randomUUID().toString(),
                        "productId", UUID.randomUUID().toString(),
                        "quantity", quantity),
                Instant.now());
    }

    @Test
    void handle_fractionalQuantity_isRejectedNotTruncated() {
        authenticateAsOwner();

        var result = handler.handle(transferWithQuantity(1.5), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid quantity");
        verifyNoInteractions(transferCommand);
    }

    @Test
    void handle_outOfRangeQuantity_isRejected() {
        authenticateAsOwner();

        var result = handler.handle(transferWithQuantity(3_000_000_000L), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).startsWith("Invalid quantity");
        verifyNoInteractions(transferCommand);
    }
}
