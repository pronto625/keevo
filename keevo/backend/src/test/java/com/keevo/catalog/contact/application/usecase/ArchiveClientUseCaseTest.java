package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.event.ClientArchivedEvent;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD tests for ArchiveClientUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArchiveClientUseCase")
class ArchiveClientUseCaseTest {

    @Mock ClientRepository clientRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ArchiveClientUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("archives client and publishes ClientArchivedEvent")
    void should_archive_and_publish_event() {
        UUID clientId = UUID.randomUUID();
        UUID actorId  = UUID.randomUUID();
        String tenant = "tenant-xyz";
        Client client = new Client(clientId, "Alice", "+22670000001", null, null, false, NOW, NOW);
        when(clientRepository.findById(clientId)).thenReturn(Optional.of(client));

        useCase.execute(clientId, actorId, tenant);

        verify(clientRepository).archive(clientId);
        ArgumentCaptor<ClientArchivedEvent> captor = ArgumentCaptor.forClass(ClientArchivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ClientArchivedEvent event = captor.getValue();
        assertThat(event.clientId()).isEqualTo(clientId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.tenantId()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("throws CLIENT_NOT_FOUND when client does not exist")
    void should_throw_when_client_not_found() {
        UUID clientId = UUID.randomUUID();
        when(clientRepository.findById(clientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(clientId, UUID.randomUUID(), "t"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLIENT_NOT_FOUND");
    }
}
