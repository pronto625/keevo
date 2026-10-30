package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.api.ClientCommandPort;
import com.keevo.crm.api.ClientView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientSyncHandlerTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT_ID);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private ClientCommandPort clientCommand;

    private ClientSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new ClientSyncHandler(clientCommand);
    }

    @Test
    void handle_updateClient_delegatesToUpdateClientPort() {
        var clientId = UUID.randomUUID();
        when(clientCommand.update(any(), any())).thenReturn(
                new ClientView(clientId, "Updated Name", "+237600000001", null, null, false, Instant.now(), Instant.now()));

        var op = new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "name", "Updated Name", "phone", "+237600000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(clientCommand).update(eq(TENANT_ID), any());
    }

    @Test
    void handle_archiveClient_delegatesToArchiveClientPort() {
        var clientId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "ARCHIVE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(clientCommand).archive(eq(TENANT_ID), any(), any());
    }

    @Test
    void handle_duplicateClient_returnsDuplicate() {
        var clientId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "name", "Name", "phone", "+237600000001"),
                Instant.now());

        doThrow(new DomainException(ErrorCode.CLIENT_NOT_FOUND))
                .when(clientCommand).update(any(), any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("CLIENT_NOT_FOUND");
    }

    @Test
    void supportedTypes_containsClientTypes() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("UPDATE_CLIENT", "ARCHIVE_CLIENT", "UNARCHIVE_CLIENT");
    }

    @Test
    void handle_unarchiveClient_delegatesToUnarchiveClientPort() {
        var clientId = UUID.randomUUID();
        var op = new SyncOperation("op-2", "UNARCHIVE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(clientId.toString());
        verify(clientCommand).unarchive(TENANT_ID, clientId, ACTOR_ID);
        verify(clientCommand, never()).archive(any(), any(), any());
    }

    @Test
    void handle_unarchiveUnknownClient_isRejected() {
        var clientId = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.CLIENT_NOT_FOUND))
                .when(clientCommand).unarchive(any(), any(), any());

        var result = handler.handle(new SyncOperation("op-3", "UNARCHIVE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString()), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("CLIENT_NOT_FOUND");
    }
}
