package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.event.ClientCreatedEvent;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD tests for CreateClientUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreateClientUseCase")
class CreateClientUseCaseTest {

    @Mock ClientRepository clientRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks CreateClientUseCase useCase;

    @Test
    @DisplayName("valid command creates client, saves, and publishes ClientCreatedEvent")
    void should_create_client_and_publish_event() {
        UUID savedId = UUID.randomUUID();
        Instant now  = Instant.now();
        var tenantId = "kv_abc123";
        var actorId  = UUID.randomUUID();

        var command = new CreateClientUseCase.CreateClientCommand(
                "Alice Dupont", "+22670000001", "alice@example.com", "VIP", actorId, tenantId);

        when(clientRepository.save(any())).thenAnswer(inv -> {
            Client c = inv.getArgument(0);
            return new Client(savedId, c.name(), c.phone(), c.email(),
                    c.notes(), c.archived(), now, now);
        });

        Client result = useCase.execute(command);

        assertThat(result.id()).isEqualTo(savedId);
        assertThat(result.name()).isEqualTo("Alice Dupont");
        verify(clientRepository).save(any(Client.class));

        ArgumentCaptor<ClientCreatedEvent> captor = ArgumentCaptor.forClass(ClientCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("blank name throws VALIDATION_ERROR before save")
    void should_throw_on_blank_name() {
        var command = new CreateClientUseCase.CreateClientCommand(
                "  ", "+22670000001", null, null, UUID.randomUUID(), "kv_abc123");

        assertThrows(DomainException.class, () -> useCase.execute(command));
        verify(clientRepository, never()).save(any());
    }

    @Test
    @DisplayName("invalid phone throws VALIDATION_ERROR before save")
    void should_throw_on_invalid_phone() {
        var command = new CreateClientUseCase.CreateClientCommand(
                "Alice", "not-a-phone", null, null, UUID.randomUUID(), "kv_abc123");

        assertThrows(DomainException.class, () -> useCase.execute(command));
        verify(clientRepository, never()).save(any());
    }
}
