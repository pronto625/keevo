package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.adapter.in.api.CommerceSaleApiTestSupport;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
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
 * CancelSaleSyncHandlerTest — Story v1s-13-5 AC5 + v1s-12-8 AC2/AC6 + 11.4 (OWNER-only, same as REST).
 */
@ExtendWith(MockitoExtension.class)
class CancelSaleSyncHandlerTest {

    @Mock private CancelPendingSaleUseCase cancelPendingSaleUseCase;

    private CancelSaleSyncHandler handler;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new CancelSaleSyncHandler(
                CommerceSaleApiTestSupport.saleCommandPort(null, null, cancelPendingSaleUseCase));
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
        return new SyncOperation("op-1", "CANCEL_SALE", SALE_ID.toString(),
                Map.of("saleId", SALE_ID.toString(), "justification", "Client a changé d'avis"),
                Instant.now());
    }

    @Test
    void apply_pendingSale_ownerSucceeds() {
        authenticateAsOwner();

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(cancelPendingSaleUseCase).cancelPendingSale(any());
    }

    // Story 11.4: cancellation is OWNER-only on REST (PendingSaleController.cancelSale) and the
    // sync channel follows the same rule — an EMPLOYEE is refused before the use case runs, in its
    // own store as in another one (supersedes the v1s-12-8 "EMPLOYEE same store → APPLIED" case).
    @Test
    void apply_pendingSale_employeeSameStore_rejectedForbidden() {
        authenticateAsEmployee(STORE_ID);

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(cancelPendingSaleUseCase, never()).cancelPendingSale(any());
    }

    @Test
    void apply_pendingSale_employeeCrossStore_rejectedForbidden() {
        authenticateAsEmployee(UUID.randomUUID());

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(cancelPendingSaleUseCase, never()).cancelPendingSale(any());
    }

    @Test
    void apply_unauthenticated_rejectedForbidden() {
        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
        verify(cancelPendingSaleUseCase, never()).cancelPendingSale(any());
    }

    @Test
    void apply_owner_sendsNoStoreScope() {
        authenticateAsOwner();

        var result = handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);

        ArgumentCaptor<CancelPendingSaleCommand> captor = ArgumentCaptor.forClass(CancelPendingSaleCommand.class);
        verify(cancelPendingSaleUseCase).cancelPendingSale(captor.capture());
        assertThat(captor.getValue().assignedStoreId()).isNull();
    }

    @Test
    void apply_alwaysSendsPendingOnly() {
        authenticateAsOwner();

        handler.handle(buildOp(), ACTOR_ID, TENANT_ID);

        ArgumentCaptor<CancelPendingSaleCommand> captor = ArgumentCaptor.forClass(CancelPendingSaleCommand.class);
        verify(cancelPendingSaleUseCase).cancelPendingSale(captor.capture());
        assertThat(captor.getValue().pendingOnly()).isTrue();
        assertThat(captor.getValue().saleId()).isEqualTo(SALE_ID);
    }

    // Story v1s-13-5 AC5 / 13.9: COMPLETED (at load or validated concurrently) is refused by the
    // use case with FORBIDDEN under pendingOnly — the handler no longer pre-checks the status.
    @Test
    void apply_completedSale_rejectedForbidden() {
        authenticateAsOwner();
        doThrow(new DomainException(ErrorCode.FORBIDDEN,
                "Cancelling a completed sale requires an online OWNER-authenticated request"))
                .when(cancelPendingSaleUseCase).cancelPendingSale(any());

        var op = new SyncOperation("op-2", "CANCEL_SALE", SALE_ID.toString(),
                Map.of("saleId", SALE_ID.toString(), "justification", "Tentative offline sur vente complétée"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
    }
}
