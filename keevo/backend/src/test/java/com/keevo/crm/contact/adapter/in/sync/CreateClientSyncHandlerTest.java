package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.api.ClientCommandPort;
import com.keevo.crm.api.ClientQueryPort;
import com.keevo.crm.api.ClientView;
import com.keevo.crm.api.CreateClientInput;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateClientSyncHandlerTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT_ID);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private ClientQueryPort clientQuery;
    @Mock private ClientCommandPort clientCommand;

    private CreateClientSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CreateClientSyncHandler(clientQuery, clientCommand);
    }

    private ClientView sampleClient(UUID id) {
        return new ClientView(id, "Alice Dupont", "+237690000001", null, null, false,
                Instant.now(), Instant.now());
    }

    @Test
    void handle_createClient_savesWithClientProvidedUUID() {
        var clientId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(false);
        when(clientCommand.createWithId(any(), any())).thenReturn(sampleClient(clientId));

        var op = new SyncOperation("op-1", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Alice Dupont", "phone", "+237690000001",
                        "notes", "VIP"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(clientId.toString());
        // Created with the exact client-provided UUID (idempotency).
        var captor = ArgumentCaptor.forClass(CreateClientInput.class);
        verify(clientCommand).createWithId(eq(TENANT_ID), captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new CreateClientInput(clientId, "Alice Dupont", "+237690000001", null, "VIP"));
    }

    @Test
    void handle_createClient_idempotent_skipsWhenClientAlreadyExists() {
        var clientId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(true);

        var op = new SyncOperation("op-2", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Alice Dupont", "phone", "+237690000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verifyNoInteractions(clientCommand);
    }

    @Test
    void handle_createClient_missingId_returnsRejected() {
        var op = new SyncOperation("op-3", "CREATE_CLIENT", null,
                Map.of("name", "Alice", "phone", "+237690000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(clientQuery, clientCommand);
    }

    @Test
    void handle_createClient_missingPhone_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_CLIENT", null,
                Map.of("id", UUID.randomUUID().toString(), "name", "Alice"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(clientQuery, clientCommand);
    }

    @Test
    void handle_createClient_invalidPhone_returnsRejected() {
        // The crm rejects a non-E.164 phone with VALIDATION_ERROR.
        var clientId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(false);
        when(clientCommand.createWithId(any(), any()))
                .thenThrow(new DomainException(ErrorCode.VALIDATION_ERROR, "Phone must be E.164 format"));

        var op = new SyncOperation("op-5", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Bob", "phone", "invalid"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void supportedTypes_containsCreateClient() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_CLIENT");
    }

    // ── v1s-13-8: @NotBlank phone parity with the REST DTO ─────────────────

    @Test
    void handle_blankPhone_returnsRejected() {
        var op = new SyncOperation("op-blank", "CREATE_CLIENT", null,
                Map.of("id", UUID.randomUUID().toString(), "name", "Alice", "phone", "  "),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(clientQuery, clientCommand);
    }
}
