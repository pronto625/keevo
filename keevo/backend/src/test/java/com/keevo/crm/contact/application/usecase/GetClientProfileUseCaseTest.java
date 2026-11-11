package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.ClientProfile;
import com.keevo.crm.contact.domain.entity.MarketingConsent;
import com.keevo.crm.contact.domain.port.out.ClientPurchaseStatsPort;
import com.keevo.crm.contact.domain.port.out.ClientPurchaseStatsPort.Stats;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.store.api.StoreQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GetClientProfileUseCase (Story 2.5; Story 20.2 reprise 11/11/2026 — purchase figures from the single revenue
 * source, documents of the member's visible stores only).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetClientProfileUseCase")
class GetClientProfileUseCaseTest {

    private static final Instant NOW = Instant.now();
    private static final String TENANT = "kv_t";

    @Mock ClientRepository clientRepository;
    @Mock ClientPurchaseStatsPort statsPort;
    @Mock StoreQueryPort storeQuery;

    GetClientProfileUseCase useCase;

    @BeforeEach
    void setUp() {
        ClientStoreScope scope = new ClientStoreScope(storeQuery);
        useCase = new GetClientProfileUseCase(new ClientFinder(clientRepository, scope), scope, statsPort);
    }

    private static Client client(UUID id, UUID storeId) {
        return new Client(id, "Alice", "+22670000001", null, null, false, NOW, NOW, ClientProfile.EMPTY, storeId,
                MarketingConsent.NONE);
    }

    @Test
    @DisplayName("owner: figures of every store (null store set), count / total / last purchase passed through")
    void ownerEveryStore() {
        UUID id = UUID.randomUUID();
        Client client = client(id, null);
        Instant last = Instant.parse("2026-11-10T09:00:00Z");
        when(clientRepository.findById(id)).thenReturn(Optional.of(client));
        when(statsPort.statsOf(eq(id), isNull())).thenReturn(new Stats(5, 150_000, last));

        var result = useCase.execute(id, ClientAccess.SYSTEM, TENANT);

        assertThat(result.client()).isEqualTo(client);
        assertThat(result.purchaseCount()).isEqualTo(5L);
        assertThat(result.totalSpentXaf()).isEqualTo(150_000L);
        assertThat(result.lastPurchaseAt()).isEqualTo(last);
        verifyNoInteractions(storeQuery);
    }

    @Test
    @DisplayName("member: only the documents of its visible stores (own + sharing), even for a legacy client")
    void memberVisibleStoresOnly() {
        UUID id = UUID.randomUUID();
        UUID storeA = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.of(client(id, null)));
        when(storeQuery.clientVisibleStoreIds(TENANT, storeA)).thenReturn(Set.of(storeA, storeB));
        when(statsPort.statsOf(eq(id), any())).thenReturn(new Stats(1, 3_000, NOW));

        var result = useCase.execute(id, ClientAccess.member(storeA), TENANT);

        assertThat(result.purchaseCount()).isEqualTo(1L);
        verify(statsPort).statsOf(id, Set.of(storeA, storeB));
    }

    @Test
    @DisplayName("member without store: an empty store set (no purchase of any store shown)")
    void memberWithoutStore() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.of(client(id, null)));
        when(statsPort.statsOf(eq(id), any())).thenReturn(Stats.NONE);

        var result = useCase.execute(id, ClientAccess.member(null), TENANT);

        assertThat(result.purchaseCount()).isZero();
        assertThat(result.lastPurchaseAt()).isNull();
        verify(statsPort).statsOf(id, Set.of());
    }

    @Test
    @DisplayName("member: a client of a store it does not see is CLIENT_NOT_FOUND, no figures read")
    void memberOtherStoreClient() {
        UUID id = UUID.randomUUID();
        UUID storeA = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.of(client(id, UUID.randomUUID())));
        when(storeQuery.clientVisibleStoreIds(TENANT, storeA)).thenReturn(Set.of(storeA));

        assertThatThrownBy(() -> useCase.execute(id, ClientAccess.member(storeA), TENANT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLIENT_NOT_FOUND");
        verifyNoInteractions(statsPort);
    }

    @Test
    @DisplayName("throws CLIENT_NOT_FOUND when client does not exist (no figures read)")
    void should_throw_when_client_not_found() {
        UUID id = UUID.randomUUID();
        when(clientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id, ClientAccess.SYSTEM, TENANT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLIENT_NOT_FOUND");
        verifyNoInteractions(statsPort);
    }
}
