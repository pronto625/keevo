package com.keevo.sync.sync.application.handler;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateClientSyncHandlerTest {

    @Mock private ClientRepository clientRepository;

    private CreateClientSyncHandler handler;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        handler = new CreateClientSyncHandler(clientRepository);
    }

    private Client sampleClient(UUID id) {
        return new Client(id, "Alice Dupont", "+237690000001", null, null, false,
                Instant.now(), Instant.now());
    }

    @Test
    void handle_createClient_savesWithClientProvidedUUID() {
        var clientId = UUID.randomUUID();
        when(clientRepository.findById(clientId)).thenReturn(Optional.empty());
        when(clientRepository.save(any())).thenReturn(sampleClient(clientId));

        var op = new SyncOperation("op-1", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Alice Dupont", "phone", "+237690000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.serverEntityId()).isEqualTo(clientId.toString());
        // Verify saved with the exact client-provided UUID (idempotency).
        verify(clientRepository).save(argThat(c -> clientId.equals(c.id())));
    }

    @Test
    void handle_createClient_idempotent_skipsWhenClientAlreadyExists() {
        var clientId = UUID.randomUUID();
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(sampleClient(clientId)));

        var op = new SyncOperation("op-2", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Alice Dupont", "phone", "+237690000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(clientRepository, never()).save(any());
    }

    @Test
    void handle_createClient_missingId_returnsRejected() {
        var op = new SyncOperation("op-3", "CREATE_CLIENT", null,
                Map.of("name", "Alice", "phone", "+237690000001"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(clientRepository);
    }

    @Test
    void handle_createClient_missingPhone_returnsRejected() {
        var op = new SyncOperation("op-4", "CREATE_CLIENT", null,
                Map.of("id", UUID.randomUUID().toString(), "name", "Alice"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(clientRepository);
    }

    @Test
    void handle_createClient_invalidPhone_returnsRejected() {
        // Client domain record throws DomainException for invalid E.164 format.
        var clientId = UUID.randomUUID();
        when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

        var op = new SyncOperation("op-5", "CREATE_CLIENT", clientId.toString(),
                Map.of("id", clientId.toString(), "name", "Bob", "phone", "invalid"),
                Instant.now());

        var result = handler.handle(op, ACTOR_ID, TENANT_ID);

        assertThat(result.status()).isEqualTo(SyncOperationStatus.REJECTED);
        verify(clientRepository, never()).save(any());
    }

    @Test
    void supportedTypes_containsCreateClient() {
        assertThat(handler.supportedTypes()).containsExactly("CREATE_CLIENT");
    }
}
