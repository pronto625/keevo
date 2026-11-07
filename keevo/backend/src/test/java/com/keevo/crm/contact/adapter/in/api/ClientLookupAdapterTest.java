package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.crm.contact.domain.service.ClientVisibility;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    void search_oneBoundedPageThroughRepository_nonArchived_nameOrder() {
        // Story 20.6 — the repository answers a name-ordered (lower(name), id), non-archived page of at most `limit`
        List<Client> page = List.of(client("Amir", "+237690000002", false), client("Mireille", "+237690000003", false));
        when(repository.findPage("mir", false, ClientVisibility.Scope.UNRESTRICTED, null, 20)).thenReturn(page);
        List<ClientSummary> found = adapter.search(TENANT, "  mir ", 20);
        assertThat(found).extracting(ClientSummary::name).containsExactly("Amir", "Mireille");
        verify(repository).findPage("mir", false, ClientVisibility.Scope.UNRESTRICTED, null, 20);
        verify(repository, never()).searchByNameOrPhone(anyString());
        verify(repository, never()).findAll(anyBoolean());
    }

    @Test
    void search_blankQuery_pagesNonArchivedClientsWithoutSearch() {
        when(repository.findPage(isNull(), eq(false), eq(ClientVisibility.Scope.UNRESTRICTED), isNull(), eq(20)))
                .thenReturn(List.of(client("Bob", "+237690000006", false), client("Zoé", "+237690000005", false)));
        assertThat(adapter.search(TENANT, " ", 20)).extracting(ClientSummary::name).containsExactly("Bob", "Zoé");
        assertThat(adapter.search(TENANT, null, 20)).hasSize(2);
        verify(repository, times(2)).findPage(null, false, ClientVisibility.Scope.UNRESTRICTED, null, 20);
        verify(repository, never()).searchByNameOrPhone(anyString());
        verify(repository, never()).findAll(anyBoolean());
    }

    @Test
    void search_limitClampedBetweenOneAndFifty_passedToTheQuery() {
        when(repository.findPage(any(), anyBoolean(), any(), any(), anyInt())).thenAnswer(i -> IntStream
                .range(0, (int) i.getArgument(4))
                .mapToObj(n -> client("Client %02d".formatted(n), "+2376900001%02d".formatted(n), false)).toList());
        assertThat(adapter.search(TENANT, "", 0)).hasSize(1);
        assertThat(adapter.search(TENANT, "", -5)).hasSize(1);
        assertThat(adapter.search(TENANT, "", 7)).hasSize(7);
        assertThat(adapter.search(TENANT, "", 500)).hasSize(50);
        verify(repository, times(2)).findPage(null, false, ClientVisibility.Scope.UNRESTRICTED, null, 1);
        verify(repository).findPage(null, false, ClientVisibility.Scope.UNRESTRICTED, null, 7);
        verify(repository).findPage(null, false, ClientVisibility.Scope.UNRESTRICTED, null, 50);
    }

    @Test
    void findAndSearch_anotherTenantThanTheContext_failFast() {
        assertThatThrownBy(() -> adapter.find("kv_other1", ID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.search("kv_other1", "a", 10)).isInstanceOf(IllegalStateException.class);
    }
}
