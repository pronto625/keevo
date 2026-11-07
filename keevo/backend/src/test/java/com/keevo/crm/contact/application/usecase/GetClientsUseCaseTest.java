package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
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
    @Mock com.keevo.store.api.StoreQueryPort storeQuery;
    GetClientsUseCase useCase;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        useCase = new GetClientsUseCase(clientRepository,
                new com.keevo.crm.contact.application.service.ClientStoreScope(storeQuery));
    }

    private static final Instant NOW = Instant.now();

    Client sampleClient(UUID id) {
        return new Client(id, "Alice", "+22670000001", null, null, false, NOW, NOW);
    }

    @Test
    @DisplayName("blank searchQuery delegates to findAll(false)")
    void should_call_find_all_when_no_search_query() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findAll(false, com.keevo.crm.contact.domain.service.ClientVisibility.Scope.UNRESTRICTED)).thenReturn(List.of(sampleClient(id)));

        List<Client> result = useCase.execute(new GetClientsUseCase.GetClientsQuery("", false));

        assertThat(result).hasSize(1);
        verify(clientRepository).findAll(false, com.keevo.crm.contact.domain.service.ClientVisibility.Scope.UNRESTRICTED);
        verify(clientRepository, never()).searchByNameOrPhone(any(), any());
    }

    @Test
    @DisplayName("non-blank searchQuery delegates to searchByNameOrPhone")
    void should_call_search_when_query_provided() {
        UUID id = UUID.randomUUID();
        when(clientRepository.searchByNameOrPhone("Ali", com.keevo.crm.contact.domain.service.ClientVisibility.Scope.UNRESTRICTED)).thenReturn(List.of(sampleClient(id)));

        List<Client> result = useCase.execute(new GetClientsUseCase.GetClientsQuery("Ali", false));

        assertThat(result).hasSize(1);
        verify(clientRepository).searchByNameOrPhone("Ali", com.keevo.crm.contact.domain.service.ClientVisibility.Scope.UNRESTRICTED);
    }

    @Test
    @DisplayName("includeArchived=true passes through to repository")
    void should_pass_include_archived_flag() {
        when(clientRepository.findAll(true, com.keevo.crm.contact.domain.service.ClientVisibility.Scope.UNRESTRICTED)).thenReturn(List.of());

        useCase.execute(new GetClientsUseCase.GetClientsQuery(null, true));

        verify(clientRepository).findAll(true, com.keevo.crm.contact.domain.service.ClientVisibility.Scope.UNRESTRICTED);
    }

    @Test
    @DisplayName("20.6 executePage reads limit + 1: the extra row only yields the cursor of the last returned one")
    void page_reads_one_more_row() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var scope = com.keevo.crm.contact.domain.service.ClientVisibility.Scope.UNRESTRICTED;
        when(clientRepository.findPage(null, false, scope, null, 2)).thenReturn(List.of(sampleClient(a),
                sampleClient(b)));

        var page = useCase.executePage(new GetClientsUseCase.GetClientsQuery(null, false), 1, null);

        assertThat(page.items()).extracting(Client::id).containsExactly(a);
        assertThat(page.next()).isEqualTo(new GetClientsUseCase.ClientCursor("Alice", a));
        when(clientRepository.findPage(null, false, scope,
                new ClientRepository.ClientKeyset("Alice", a), 2)).thenReturn(List.of(sampleClient(b)));
        var last = useCase.executePage(new GetClientsUseCase.GetClientsQuery(null, false), 1, page.next());
        assertThat(last.items()).extracting(Client::id).containsExactly(b);
        assertThat(last.next()).isNull();
    }

    @Test
    @DisplayName("20.6 cursor round trip (name with ':' and accents); garbage → IllegalArgumentException")
    void cursor_round_trip() {
        var cursor = new GetClientsUseCase.ClientCursor("Éts Ngo : Akwa", UUID.randomUUID());
        assertThat(GetClientsUseCase.ClientCursor.decode(cursor.encode())).isEqualTo(cursor);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GetClientsUseCase.ClientCursor.decode("x!"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GetClientsUseCase.ClientCursor.decode(
                java.util.Base64.getUrlEncoder().encodeToString("pas-un-uuid-du-tout-pas-un-uuid-du-t:x".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
