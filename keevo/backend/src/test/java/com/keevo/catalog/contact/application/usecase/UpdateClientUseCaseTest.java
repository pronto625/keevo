package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD tests for UpdateClientUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateClientUseCase")
class UpdateClientUseCaseTest {

    @Mock ClientRepository clientRepository;
    @InjectMocks UpdateClientUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("updates provided fields and saves the updated client")
    void should_update_fields_and_save() {
        UUID id = UUID.randomUUID();
        Client existing = new Client(id, "Alice", "+22670000001", null, null, false, NOW, NOW);
        when(clientRepository.findById(id)).thenReturn(Optional.of(existing));
        when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new UpdateClientUseCase.UpdateClientCommand(
                id, "Alice Updated", null, "alice@mail.com", null, UUID.randomUUID());
        Client result = useCase.execute(cmd);

        assertThat(result.name()).isEqualTo("Alice Updated");
        assertThat(result.phone()).isEqualTo("+22670000001"); // unchanged
        assertThat(result.email()).isEqualTo("alice@mail.com");
        verify(clientRepository).save(any());
    }

    @Test
    @DisplayName("throws CLIENT_NOT_FOUND when client does not exist")
    void should_throw_when_client_not_found() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.empty());
        var cmd = new UpdateClientUseCase.UpdateClientCommand(
                id, "New Name", null, null, null, UUID.randomUUID());

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLIENT_NOT_FOUND");
    }

    @Test
    @DisplayName("null command fields keep existing values")
    void should_keep_existing_values_when_null_fields() {
        UUID id = UUID.randomUUID();
        Client existing = new Client(id, "Bob", "+22670000002", "bob@x.com", "note", false, NOW, NOW);
        when(clientRepository.findById(id)).thenReturn(Optional.of(existing));
        when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var cmd = new UpdateClientUseCase.UpdateClientCommand(
                id, null, null, null, null, UUID.randomUUID());
        Client result = useCase.execute(cmd);

        assertThat(result.name()).isEqualTo("Bob");
        assertThat(result.phone()).isEqualTo("+22670000002");
        assertThat(result.email()).isEqualTo("bob@x.com");
        assertThat(result.notes()).isEqualTo("note");
    }
}
