package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.keevo.crm.api.ClientSummary;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 13.6 — the crm {@code ClientLookupPort} capability reads the repository of the current tenant. */
class ClientLookupAdapterTest {

    private static final String TENANT = "kv_look01";
    private static final UUID ID = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    private final ClientRepository repository = mock(ClientRepository.class);
    private final ClientLookupAdapter adapter = new ClientLookupAdapter(repository);

    @BeforeEach
    void bind() {
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void knownClient_nameAndExistence_archivedIncluded() {
        when(repository.findById(ID)).thenReturn(Optional.of(
                new Client(ID, "Alice", "+237690000001", null, null, true, T0, T0)));
        assertThat(adapter.displayName(TENANT, ID)).contains("Alice");
        assertThat(adapter.exists(TENANT, ID)).isTrue();
    }

    @Test
    void unknownOrNullClient_emptyAndFalse() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThat(adapter.displayName(TENANT, unknown)).isEmpty();
        assertThat(adapter.exists(TENANT, unknown)).isFalse();
        assertThat(adapter.displayName(TENANT, null)).isEmpty();
        assertThat(adapter.exists(TENANT, null)).isFalse();
    }

    @Test
    void anotherTenantThanTheContext_failsFast() {
        assertThatThrownBy(() -> adapter.displayName("kv_other1", ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.exists("kv_other1", ID)).isInstanceOf(IllegalStateException.class);
    }

    // --- Story 14.3: summary lookup and bounded search -------------------------------------------------------

    private static Client client(String name, String phone, boolean archived) {
        return new Client(UUID.randomUUID(), name, phone, null, null, archived, T0, T0);
    }

    @Test
    void find_returnsSummary_archivedIncluded_unknownOrNullEmpty() {
        when(repository.findById(ID)).thenReturn(Optional.of(
                new Client(ID, "Alice", "+237690000001", null, null, true, T0, T0)));
        assertThat(adapter.find(TENANT, ID)).contains(new ClientSummary(ID, "Alice", "+237690000001", true));
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThat(adapter.find(TENANT, unknown)).isEmpty();
        assertThat(adapter.find(TENANT, null)).isEmpty();
    }

    @Test
    void search_matchesThroughRepository_excludesArchived_sortedByName() {
        when(repository.searchByNameOrPhone("mir")).thenReturn(List.of(
                client("Mireille", "+237690000003", false),
                client("Amir", "+237690000002", false),
                client("Miriam (archivée)", "+237690000004", true)));
        List<ClientSummary> found = adapter.search(TENANT, "  mir ", 20);
        assertThat(found).extracting(ClientSummary::name).containsExactly("Amir", "Mireille");
        verify(repository).searchByNameOrPhone("mir");
    }

    @Test
    void search_blankQuery_listsNonArchivedClients() {
        when(repository.findAll(false)).thenReturn(List.of(client("Zoé", "+237690000005", false),
                client("Bob", "+237690000006", false)));
        assertThat(adapter.search(TENANT, " ", 20)).extracting(ClientSummary::name).containsExactly("Bob", "Zoé");
        assertThat(adapter.search(TENANT, null, 20)).hasSize(2);
        verify(repository, never()).searchByNameOrPhone(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void search_limitClampedBetweenOneAndFifty() {
        List<Client> many = IntStream.range(0, 80)
                .mapToObj(i -> client("Client %02d".formatted(i), "+2376900001%02d".formatted(i), false)).toList();
        when(repository.findAll(false)).thenReturn(many);
        assertThat(adapter.search(TENANT, "", 0)).hasSize(1);
        assertThat(adapter.search(TENANT, "", -5)).hasSize(1);
        assertThat(adapter.search(TENANT, "", 7)).hasSize(7);
        assertThat(adapter.search(TENANT, "", 500)).hasSize(50);
    }

    @Test
    void findAndSearch_anotherTenantThanTheContext_failFast() {
        assertThatThrownBy(() -> adapter.find("kv_other1", ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.search("kv_other1", "a", 10)).isInstanceOf(IllegalStateException.class);
    }
}
