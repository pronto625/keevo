package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.event.ClientUnarchivedEvent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnarchiveClientUseCase")
class UnarchiveClientUseCaseTest {

    @Mock ClientRepository clientRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks UnarchiveClientUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("restores an archived client and publishes ClientUnarchivedEvent")
    void restoresAndPublishes() {
        UUID id = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.of(client(id, true)));

        useCase.execute(id, actorId, "tenant-xyz");

        verify(clientRepository).unarchive(id);
        ArgumentCaptor<ClientUnarchivedEvent> captor = ArgumentCaptor.forClass(ClientUnarchivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().clientId()).isEqualTo(id);
        assertThat(captor.getValue().name()).isEqualTo("Alice");
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().tenantId()).isEqualTo("tenant-xyz");
    }

    @Test
    @DisplayName("an already active client (replayed operation) is left as is, no audit")
    void alreadyActiveIsNoOp() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.of(client(id, false)));

        useCase.execute(id, UUID.randomUUID(), "t");

        verify(clientRepository, never()).unarchive(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("throws CLIENT_NOT_FOUND when the client does not exist")
    void unknown() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id, UUID.randomUUID(), "t"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLIENT_NOT_FOUND");
    }

    private static Client client(UUID id, boolean archived) {
        return new Client(id, "Alice", "+22670000001", null, null, archived, NOW, NOW);
    }
}
