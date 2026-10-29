package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
}
