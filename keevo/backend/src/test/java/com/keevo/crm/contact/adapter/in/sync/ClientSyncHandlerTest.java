package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.contact.ClientTestActors;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.usecase.ArchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.SetClientMarketingConsentUseCase;
import com.keevo.crm.contact.application.usecase.UnarchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.UpdateClientUseCase;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientSyncHandlerTest {

    @Mock private UpdateClientUseCase updateClient;
    @Mock private ArchiveClientUseCase archiveClient;
    @Mock private UnarchiveClientUseCase unarchiveClient;
    @Mock private SetClientMarketingConsentUseCase setConsent;

    private ClientSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID STORE_A = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new ClientSyncHandler(updateClient, archiveClient, unarchiveClient, setConsent);
        ClientTestActors.owner(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        ClientTestActors.clear();
    }

    private static Client client(UUID id, String name) {
        return new Client(id, name, "+237600000001", null, null, false, Instant.now(), Instant.now());
    }

    @Test
    void handle_updateClient_delegatesToTheUseCase_withBrefFieldsAndStore() {
        var clientId = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();
        when(updateClient.execute(any())).thenReturn(client(clientId, "Updated Name"));
        Map<String, Object> payload = new HashMap<>(Map.of("clientId", clientId.toString(), "name", "Updated Name",
                "phone", "+237600000001", "niu", "M01", "birthDate", "", "storeId", storeB.toString()));

        var result = handler.handle(new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(), payload,
                Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        var captor = ArgumentCaptor.forClass(UpdateClientUseCase.UpdateClientCommand.class);
        verify(updateClient).execute(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.profile()).isEqualTo(new ClientProfileInput(null, "M01", null, null, null, null, ""));
        assertThat(cmd.requestedStoreId()).isEqualTo(storeB);
        assertThat(cmd.access()).isEqualTo(ClientAccess.SYSTEM);
        assertThat(cmd.tenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void handle_updateClient_ofAStoreMember_carriesItsAccess() {
        ClientTestActors.member(ACTOR_ID, STORE_A, "EMPLOYEE");
        var clientId = UUID.randomUUID();
        when(updateClient.execute(any())).thenReturn(client(clientId, "N"));

        handler.handle(new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "name", "N"), Instant.now()), ACTOR_ID, TENANT_ID);

        var captor = ArgumentCaptor.forClass(UpdateClientUseCase.UpdateClientCommand.class);
        verify(updateClient).execute(captor.capture());
        assertThat(captor.getValue().access()).isEqualTo(ClientAccess.member(STORE_A));
    }

    @Test
    void handle_archiveClient_delegatesToTheUseCase() {
        var clientId = UUID.randomUUID();
        var result = handler.handle(new SyncOperation("op-1", "ARCHIVE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString()), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(archiveClient).execute(clientId, ACTOR_ID, TENANT_ID, ClientAccess.SYSTEM);
    }

    @Test
    void handle_clientOutOfSight_isRejectedClientNotFound() {
        ClientTestActors.member(ACTOR_ID, STORE_A, "EMPLOYEE");
        var clientId = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.CLIENT_NOT_FOUND)).when(updateClient).execute(any());

        var result = handler.handle(new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "name", "Name"), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("CLIENT_NOT_FOUND");
    }

    @Test
    void handle_memberMovingAClient_isRejectedForbidden() {
        ClientTestActors.member(ACTOR_ID, STORE_A, "EMPLOYEE");
        var clientId = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.FORBIDDEN, "x")).when(updateClient).execute(any());

        var result = handler.handle(new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "storeId", UUID.randomUUID().toString()), Instant.now()),
                ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("FORBIDDEN");
    }

    @Test
    void handle_badStoreIdType_isRejected() {
        var clientId = UUID.randomUUID();
        var result = handler.handle(new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "storeId", 12), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(updateClient);
    }

    @Test
    void supportedTypes_containsClientTypes() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("UPDATE_CLIENT", "ARCHIVE_CLIENT",
                "UNARCHIVE_CLIENT", "SET_CLIENT_MARKETING_CONSENT");
    }

    @Test
    void handle_unarchiveClient_delegatesToTheUseCase() {
        var clientId = UUID.randomUUID();
        var result = handler.handle(new SyncOperation("op-2", "UNARCHIVE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString()), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(clientId.toString());
        verify(unarchiveClient).execute(clientId, ACTOR_ID, TENANT_ID, ClientAccess.SYSTEM);
        verify(archiveClient, never()).execute(any(), any(), any(), any());
    }

    @Test
    void handle_unarchiveUnknownClient_isRejected() {
        var clientId = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.CLIENT_NOT_FOUND))
                .when(unarchiveClient).execute(any(), any(), any(), any());

        var result = handler.handle(new SyncOperation("op-3", "UNARCHIVE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString()), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("CLIENT_NOT_FOUND");
    }

    // ── Story 20.2 — SET_CLIENT_MARKETING_CONSENT ──────────────────────────

    @Test
    void handle_setConsent_givenAndWithdrawn() {
        ClientTestActors.member(ACTOR_ID, STORE_A, "EMPLOYEE");
        var clientId = UUID.randomUUID();

        var given = handler.handle(new SyncOperation("op-c1", "SET_CLIENT_MARKETING_CONSENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "consent", true), Instant.now()), ACTOR_ID, TENANT_ID);
        var withdrawn = handler.handle(new SyncOperation("op-c2", "SET_CLIENT_MARKETING_CONSENT",
                clientId.toString(), Map.of("clientId", clientId.toString(), "consent", false), Instant.now()),
                ACTOR_ID, TENANT_ID);

        assertThat(given.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(withdrawn.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(setConsent).execute(clientId, true, ACTOR_ID, TENANT_ID, ClientAccess.member(STORE_A));
        verify(setConsent).execute(clientId, false, ACTOR_ID, TENANT_ID, ClientAccess.member(STORE_A));
    }

    @Test
    void handle_setConsent_withoutBooleanConsent_isRejected() {
        var clientId = UUID.randomUUID();
        var result = handler.handle(new SyncOperation("op-c3", "SET_CLIENT_MARKETING_CONSENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "consent", "yes"), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(setConsent);
    }

    @Test
    void handle_setConsent_onAClientOutOfSight_isRejectedClientNotFound() {
        var clientId = UUID.randomUUID();
        doThrow(new DomainException(ErrorCode.CLIENT_NOT_FOUND)).when(setConsent)
                .execute(any(), anyBoolean(), any(), any(), any());

        var result = handler.handle(new SyncOperation("op-c4", "SET_CLIENT_MARKETING_CONSENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "consent", true), Instant.now()), ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("CLIENT_NOT_FOUND");
    }
}
