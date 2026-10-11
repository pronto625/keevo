package com.keevo.sync.sync.application.handler;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** v1s-12-10: shared RBAC helpers — null auth fails closed (CR 12-8 D2), actorRole(). */
class AbstractSyncOperationHandlerTest {

    /** Minimal handler exposing the protected helpers. */
    private static final class ProbeHandler extends AbstractSyncOperationHandler {
        @Override public Set<String> supportedTypes() { return Set.of("PROBE"); }
        @Override protected void validate(SyncOperation operation) { }
        @Override protected SyncOperationResult apply(SyncOperation op, UUID actorId, String tenantId) {
            UUID store = extractAssignedStoreId();
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.APPLIED,
                    store == null ? null : store.toString(), null);
        }
    }

    private final ProbeHandler handler = new ProbeHandler();
    private final SyncOperation op = new SyncOperation("op-1", "PROBE", null, Map.of(), Instant.now());

    @AfterEach
    void tearDown() {
        SyncTestAuth.clear();
    }

    @Test
    void extractAssignedStoreId_withoutAuthentication_failsClosed() {
        assertThatThrownBy(handler::extractAssignedStoreId)
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void handle_withoutAuthentication_isRejectedForbidden() {
        var result = handler.handle(op, UUID.randomUUID(), "kv_test");

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
    }

    @Test
    void handle_owner_isUnscoped() {
        SyncTestAuth.owner();

        var result = handler.handle(op, UUID.randomUUID(), "kv_test");

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isNull();
    }

    @Test
    void handle_employee_returnsAssignedStore() {
        UUID store = UUID.randomUUID();
        SyncTestAuth.employee(store);

        var result = handler.handle(op, UUID.randomUUID(), "kv_test");

        assertThat(result.serverEntityId()).isEqualTo(store.toString());
    }

    @Test
    void actorRole_readsAuthorities() {
        assertThat(handler.actorRole()).isNull();
        SyncTestAuth.owner();
        assertThat(handler.actorRole()).isEqualTo("OWNER");
        SyncTestAuth.employee(UUID.randomUUID());
        assertThat(handler.actorRole()).isEqualTo("EMPLOYEE");
    }
}
