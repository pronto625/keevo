package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.application.usecase.ArchiveClientUseCase;
import com.keevo.catalog.contact.application.usecase.UpdateClientUseCase;
import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
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

    @Mock private UpdateClientUseCase updateClient;
    @Mock private ArchiveClientUseCase archiveClient;

    private ClientSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        handler = new ClientSyncHandler(updateClient, archiveClient);
    }

    @Test
    void handle_updateClient_delegatesToUpdateClientUseCase() {
        var clientId = UUID.randomUUID();
        when(updateClient.execute(any())).thenReturn(
                new Client(clientId, "Updated Name", "+237600000001", null, null, false, Instant.now(), Instant.now()));

        var op = new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "name", "Updated Name", "phone", "+237600000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(updateClient).execute(any());
    }

    @Test
    void handle_archiveClient_delegatesToArchiveClientUseCase() {
        var clientId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "ARCHIVE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString()), Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(archiveClient).execute(any(), any(), any());
    }

    @Test
    void handle_duplicateClient_returnsDuplicate() {
        var clientId = UUID.randomUUID();
        var op = new SyncOperation("op-1", "UPDATE_CLIENT", clientId.toString(),
                Map.of("clientId", clientId.toString(), "name", "Name", "phone", "+237600000001"),
                Instant.now());

        doThrow(new DomainException(ErrorCode.CLIENT_NOT_FOUND))
                .when(updateClient).execute(any());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("CLIENT_NOT_FOUND");
    }

    @Test
    void supportedTypes_containsClientTypes() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("UPDATE_CLIENT", "ARCHIVE_CLIENT");
    }
}
