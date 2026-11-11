package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientView;
import com.keevo.crm.api.UpdateClientInput;
import com.keevo.crm.contact.application.usecase.ArchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.UnarchiveClientUseCase;
import com.keevo.crm.contact.application.usecase.UpdateClientUseCase;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 12.2a — the crm client ports delegate to the repository / use cases and map faithfully. */
@ExtendWith(MockitoExtension.class)
class ClientApiAdapterTest {

    // 12.2e: the catalog / crm / inventory / commerce API adapters require an explicit, current tenant schema
    @org.junit.jupiter.api.BeforeEach
    void bindTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant(TENANT);
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext12e() {
        com.keevo.shared.infrastructure.persistence.TenantContext.clear();
    }

    @Mock private ClientRepository clientRepository;
    @Mock private UpdateClientUseCase updateClient;
    @Mock private ArchiveClientUseCase archiveClient;
    @Mock private UnarchiveClientUseCase unarchiveClient;
    @Mock private com.keevo.crm.contact.application.usecase.CreateClientUseCase createClient;
    @Mock private com.keevo.crm.contact.application.usecase.SetClientMarketingConsentUseCase consent;
    @Mock private com.keevo.crm.contact.domain.port.out.MarketingAudienceQuery purchaseAudience;

    private ClientApiAdapter adapter;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String TENANT = "kv_test";
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ClientApiAdapter(clientRepository, updateClient, archiveClient, unarchiveClient, createClient,
                consent, purchaseAudience);
    }

    @Test
    void existsById_reflectsTheRepository() {
        when(clientRepository.findById(ID)).thenReturn(Optional.of(
                new Client(ID, "Alice", "+237690000001", null, null, false, T0, T0)));

        UUID unknown = UUID.randomUUID();
        when(clientRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(adapter.existsById(TenantContext.getCurrentTenant(), ID)).isTrue();
        assertThat(adapter.existsById(TenantContext.getCurrentTenant(), unknown)).isFalse();
    }

    @Test
    void update_mapsOntoTheUseCaseCommand() {
        when(updateClient.execute(any())).thenReturn(
                new Client(ID, "Alice B", "+237690000002", null, "n", false, T0, T0));

        ClientView view = adapter.update(TenantContext.getCurrentTenant(), new UpdateClientInput(ID, "Alice B", "+237690000002", null, "n", ACTOR));

        verify(updateClient).execute(new UpdateClientUseCase.UpdateClientCommand(
                ID, "Alice B", "+237690000002", null, "n", ACTOR, com.keevo.crm.api.ClientProfileInput.NONE, null,
                com.keevo.crm.contact.application.service.ClientAccess.SYSTEM, TENANT));
        assertThat(view.name()).isEqualTo("Alice B");
    }

    @Test
    void create_20_13_mapsOntoTheUseCaseWithTheActorAndReturnsTheDuplicateCount() {
        Client created = new Client(ID, "Alice", "+237690000001", null, null, false, T0, T0);
        Client other = new Client(UUID.randomUUID(), "Alice bis", "+237690000001", null, null, false, T0, T0);
        when(createClient.execute(any())).thenReturn(
                new com.keevo.crm.contact.application.usecase.CreateClientUseCase.CreateClientResult(created,
                        java.util.List.of(other)));
        UUID store = UUID.randomUUID();

        com.keevo.crm.api.CreatedClient result = adapter.create(TENANT, new com.keevo.crm.api.CreateClientInput(
                "Alice", "+237690000001", null, null, null, store, ACTOR));

        verify(createClient).execute(new com.keevo.crm.contact.application.usecase.CreateClientUseCase.CreateClientCommand(
                null, "Alice", "+237690000001", null, null, com.keevo.crm.api.ClientProfileInput.NONE, store,
                com.keevo.crm.contact.application.service.ClientAccess.SYSTEM, ACTOR, TENANT, true));
        assertThat(result.client().id()).isEqualTo(ID);
        assertThat(result.duplicates()).isEqualTo(1);
    }

    @Test
    void marketingConsent_20_13_delegatesWithTheActor() {
        when(consent.execute(ID, true, ACTOR, TENANT, com.keevo.crm.contact.application.service.ClientAccess.SYSTEM))
                .thenReturn(new Client(ID, "Alice", "+237690000001", null, null, false, T0, T0));

        assertThat(adapter.setMarketingConsent(TENANT, ID, true, ACTOR).id()).isEqualTo(ID);
    }

    @Test
    void archive_and_unarchive_delegate() {
        adapter.archive(TENANT, ID, ACTOR);
        adapter.unarchive(TENANT, ID, ACTOR);

        verify(archiveClient).execute(ID, ACTOR, TENANT, com.keevo.crm.contact.application.service.ClientAccess.SYSTEM);
        verify(unarchiveClient).execute(ID, ACTOR, TENANT, com.keevo.crm.contact.application.service.ClientAccess.SYSTEM);
    }

    @Test
    void marketingEligible_delegatesToOneRepositoryQuery_20_2() {
        UUID other = UUID.randomUUID();
        when(clientRepository.findMarketingEligible(java.util.List.of(ID, other))).thenReturn(java.util.Set.of(ID));

        assertThat(adapter.marketingEligible(TENANT, java.util.List.of(ID, other))).containsExactly(ID);
    }

    @Test
    void marketingAudience_emptyPurchaseFilterKeepsThe21_6Query_21_11() {
        java.util.List<UUID> stores = java.util.List.of(ID);
        when(clientRepository.countMarketingAudience(stores, 10)).thenReturn(4);
        when(clientRepository.findMarketingAudience(stores, 10)).thenReturn(java.util.List.of());

        assertThat(adapter.countMarketingAudience(TENANT, stores, com.keevo.crm.api.PurchaseFilter.NONE, 10))
                .isEqualTo(4);
        assertThat(adapter.countMarketingAudience(TENANT, stores, null, 10)).isEqualTo(4);
        assertThat(adapter.marketingAudience(TENANT, stores, com.keevo.crm.api.PurchaseFilter.NONE, 10)).isEmpty();
        verifyNoInteractions(purchaseAudience);

        var filter = new com.keevo.crm.api.PurchaseFilter(T0, null, null, null, 2, null);
        when(purchaseAudience.count(stores, filter, 10)).thenReturn(1);
        assertThat(adapter.countMarketingAudience(TENANT, stores, filter, 10)).isEqualTo(1);
        adapter.marketingAudience(TENANT, stores, filter, 10);
        verify(purchaseAudience).audience(stores, filter, 10);
    }

    // 12.2e: the explicit tenantSchema must be the bound tenant — another or a missing schema fails fast
    @org.junit.jupiter.api.Test
    void tenantGuard_otherOrMissingSchemaFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.existsById("kv_other1", ID))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.existsById(null, ID))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.archive("kv_other1", ID, ACTOR))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.archive(null, ID, ACTOR))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(clientRepository, archiveClient);
    }
}
