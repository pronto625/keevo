package com.keevo.sync.sync.application.handler;

import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
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
 * ValidateSaleSyncHandlerTest — Story v1s-12-8 AC1/AC6.
 * No dedicated test existed before this story.
 */
@ExtendWith(MockitoExtension.class)
class ValidateSaleSyncHandlerTest {

    @Mock private ValidateSaleUseCase validateSaleUseCase;

    private ValidateSaleSyncHandler handler;

    private static final UUID SALE_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new ValidateSaleSyncHandler(validateSaleUseCase);
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
        return new SyncOperation("op-1", "VALIDATE_SALE", SALE_ID.toString(),
                Map.of("saleId", SALE_ID.toString()), Instant.now());
    }

    @Test
    void apply_employeeSameStore_applied() {
        authenticateAsEmployee(STORE_ID);

        var op = buildOp();
        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        // Review P2: ArgumentCaptor verifies the handler actually transmitted
        // the assignedStoreId from the SecurityContext (not null).
        ArgumentCaptor<ValidateSaleCommand> captor = ArgumentCaptor.forClass(ValidateSaleCommand.class);
        verify(validateSaleUseCase).validateSale(captor.capture());
        assertThat(captor.getValue().assignedStoreId()).isEqualTo(STORE_ID);
    }

    @Test
    void apply_employeeCrossStore_rejectedForbidden() {
        UUID otherStoreId = UUID.randomUUID();
        authenticateAsEmployee(otherStoreId);

        // Review P2: doAnswer captures the command to verify assignedStoreId was
        // correctly transmitted, then simulates enforceEmployeeStoreScope() rejection.
        doAnswer(invocation -> {
            ValidateSaleCommand cmd = invocation.getArgument(0);
            assertThat(cmd.assignedStoreId()).isEqualTo(otherStoreId);
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Employee cannot operate pending sale outside assigned store");
        }).when(validateSaleUseCase).validateSale(any());

        var op = buildOp();
        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
    }

    @Test
    void apply_owner_unscoped() {
        authenticateAsOwner();

        var op = buildOp();
        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(validateSaleUseCase).validateSale(any());
    }

    @Test
    void apply_saleNotPending_idempotentApplied() {
        authenticateAsOwner();

        doThrow(new DomainException(ErrorCode.SALE_NOT_PENDING, "not pending"))
                .when(validateSaleUseCase).validateSale(any());

        var op = buildOp();
        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
    }
}
