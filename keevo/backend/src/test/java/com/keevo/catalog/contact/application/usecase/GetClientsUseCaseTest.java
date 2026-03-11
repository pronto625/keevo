package com.keevo.catalog.contact.application.usecase;

import com.keevo.catalog.contact.domain.entity.Client;
import com.keevo.catalog.contact.domain.port.out.ClientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * TDD tests for GetClientsUseCase (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetClientsUseCase")
class GetClientsUseCaseTest {

    @Mock ClientRepository clientRepository;
    @InjectMocks GetClientsUseCase useCase;

    private static final Instant NOW = Instant.now();

    Client sampleClient(UUID id) {
        return new Client(id, "Alice", "+22670000001", null, null, false, NOW, NOW);
    }

    @Test
    @DisplayName("blank searchQuery delegates to findAll(false)")
    void should_call_find_all_when_no_search_query() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findAll(false)).thenReturn(List.of(sampleClient(id)));

        List<Client> result = useCase.execute(new GetClientsUseCase.GetClientsQuery("", false));

        assertThat(result).hasSize(1);
        verify(clientRepository).findAll(false);
        verify(clientRepository, never()).searchByNameOrPhone(any());
    }

    @Test
    @DisplayName("non-blank searchQuery delegates to searchByNameOrPhone")
    void should_call_search_when_query_provided() {
        UUID id = UUID.randomUUID();
        when(clientRepository.searchByNameOrPhone("Ali")).thenReturn(List.of(sampleClient(id)));

        List<Client> result = useCase.execute(new GetClientsUseCase.GetClientsQuery("Ali", false));

        assertThat(result).hasSize(1);
        verify(clientRepository).searchByNameOrPhone("Ali");
    }

    @Test
    @DisplayName("includeArchived=true passes through to repository")
    void should_pass_include_archived_flag() {
        when(clientRepository.findAll(true)).thenReturn(List.of());

        useCase.execute(new GetClientsUseCase.GetClientsQuery(null, true));

        verify(clientRepository).findAll(true);
    }
}
