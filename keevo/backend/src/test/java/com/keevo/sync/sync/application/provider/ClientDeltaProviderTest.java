package com.keevo.sync.sync.application.provider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientDeltaProviderTest {
    @Mock private EntityManager em;
    @Mock private Query query;
    private ClientDeltaProvider provider;

    @BeforeEach
    void setUp() { provider = new ClientDeltaProvider(em); }

    @Test
    void entityKey_returnsClients() { assertThat(provider.entityKey()).isEqualTo("clients"); }

    @Test
    void queryDelta_withSince_returnsOnlyUpdatedClients() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(eq("since"), any())).thenReturn(query);
        when(query.setMaxResults(1000)).thenReturn(query);
        var now = Timestamp.from(Instant.now());
        Object[] row = {UUID.randomUUID(), "Client A", "+237600000001", "a@b.com", "notes", false, now, now};
        when(query.getResultList()).thenReturn(Collections.singletonList(row));

        var result = provider.queryDelta(Instant.parse("2026-03-20T10:00:00Z"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "Client A");
    }
}
