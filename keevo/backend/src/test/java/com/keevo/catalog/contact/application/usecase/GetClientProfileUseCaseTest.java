package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import com.keevo.catalog.contact.domain.port.out.ClientSalesStatsPort;
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
import static org.mockito.Mockito.when;

/**
 * TDD tests for GetClientProfileUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetClientProfileUseCase")
class GetClientProfileUseCaseTest {

    @Mock ClientRepository clientRepository;
    @Mock ClientSalesStatsPort statsPort;
    @InjectMocks GetClientProfileUseCase useCase;

    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("returns profile with stats for existing client")
    void should_return_profile_with_stats() {
        UUID id = UUID.randomUUID();
        Client client = new Client(id, "Alice", "+22670000001", null, null, false, NOW, NOW);
        when(clientRepository.findById(id)).thenReturn(Optional.of(client));
        when(statsPort.countSalesByClient(id)).thenReturn(5L);
        when(statsPort.totalSpentByClient(id)).thenReturn(150_000L);

        var result = useCase.execute(id);

        assertThat(result.client()).isEqualTo(client);
        assertThat(result.purchaseCount()).isEqualTo(5L);
        assertThat(result.totalSpentXaf()).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("throws CLIENT_NOT_FOUND when client does not exist")
    void should_throw_when_client_not_found() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLIENT_NOT_FOUND");
    }
}
