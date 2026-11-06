package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.api.ClientQueryPort;
import com.keevo.crm.contact.ClientTestActors;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.usecase.CreateClientUseCase;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateClientSyncHandlerTest {

    @Mock private ClientQueryPort clientQuery;
    @Mock private CreateClientUseCase createClient;

    private CreateClientSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_A = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CreateClientSyncHandler(clientQuery, createClient);
        ClientTestActors.owner(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        ClientTestActors.clear();
    }

    private static Client client(UUID id, UUID storeId) {
        return new Client(id, "Alice Dupont", "+237690000001", null, null, false, Instant.now(), Instant.now(),
                null, storeId, null);
    }

    @Test
    void handle_createClient_savesWithClientProvidedUUID_withoutAuditEvent() {
        var clientId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(false);
        when(createClient.execute(any())).thenReturn(
                new CreateClientUseCase.CreateClientResult(client(clientId, STORE_A), List.of()));

        var op = new SyncOperation("op-1", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Alice Dupont", "phone", "+237690000001",
                        "notes", "VIP"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(clientId.toString());
        assertThat(result.reason()).isNull();
        var captor = ArgumentCaptor.forClass(CreateClientUseCase.CreateClientCommand.class);
        verify(createClient).execute(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new CreateClientUseCase.CreateClientCommand(clientId,
                "Alice Dupont", "+237690000001", null, "VIP", ClientProfileInput.NONE, null, ClientAccess.SYSTEM,
                ACTOR_ID, TENANT_ID, false));
    }

    @Test
    void handle_createClient_ofAMember_carriesBrefFieldsStoreAndAccess() {
        ClientTestActors.member(ACTOR_ID, STORE_A, "EMPLOYEE");
        var clientId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(false);
        when(createClient.execute(any())).thenReturn(
                new CreateClientUseCase.CreateClientResult(client(clientId, STORE_A), List.of()));

        handler.handle(new SyncOperation("op-1", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "SARL", "phone", "699001122", "clientType", "COMPANY",
                        "rccm", "RC/DLA/2020", "storeId", STORE_A.toString()), Instant.now()), ACTOR_ID, TENANT_ID);

        var captor = ArgumentCaptor.forClass(CreateClientUseCase.CreateClientCommand.class);
        verify(createClient).execute(captor.capture());
        assertThat(captor.getValue().profile())
                .isEqualTo(new ClientProfileInput("COMPANY", null, "RC/DLA/2020", null, null, null, null));
        assertThat(captor.getValue().requestedStoreId()).isEqualTo(STORE_A);
        assertThat(captor.getValue().access()).isEqualTo(ClientAccess.member(STORE_A));
    }

    @Test
    void handle_duplicatePhoneInTheSameStore_isAppliedWithAWarning() {
        var clientId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(false);
        when(createClient.execute(any())).thenReturn(new CreateClientUseCase.CreateClientResult(
                client(clientId, STORE_A), List.of(client(existingId, STORE_A))));

        var result = handler.handle(new SyncOperation("op-d", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Alice", "phone", "699001122"), Instant.now()),
                ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(clientId.toString());
        assertThat(result.reason()).isEqualTo("DUPLICATE_PHONE");
        assertThat(result.conflictData()).containsEntry("existingClientId", existingId.toString());
    }

    @Test
    void handle_memberForgingAnotherStore_isRejectedForbidden() {
        ClientTestActors.member(ACTOR_ID, STORE_A, "EMPLOYEE");
        var clientId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(false);
        when(createClient.execute(any())).thenThrow(new DomainException(ErrorCode.FORBIDDEN, "x"));

        var result = handler.handle(new SyncOperation("op-f", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Alice", "phone", "699001122",
                        "storeId", UUID.randomUUID().toString()), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
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
        verifyNoInteractions(createClient);
    }

    @Test
    void handle_createClient_missingId_returnsRejected() {
        var op = new SyncOperation("op-3", "CREATE_CLIENT", null,
                Map.of("name", "Alice", "phone", "+237690000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(clientQuery, createClient);
    }

    @Test
    void handle_createClient_missingPhone_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_CLIENT", null,
                Map.of("id", UUID.randomUUID().toString(), "name", "Alice"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(clientQuery, createClient);
    }

    @Test
    void handle_createClient_invalidPhone_returnsRejected() {
        var clientId = UUID.randomUUID();
        when(clientQuery.existsById(TENANT_ID, clientId)).thenReturn(false);
        when(createClient.execute(any()))
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
        verifyNoInteractions(clientQuery, createClient);
    }
}
