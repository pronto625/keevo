package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientView;
import com.keevo.crm.api.CreateClientInput;
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

    private ClientApiAdapter adapter;

    private static final UUID ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String TENANT = "kv_test";
    private static final Instant T0 = Instant.parse("2026-03-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ClientApiAdapter(clientRepository, updateClient, archiveClient, unarchiveClient);
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
    void createWithId_savesANonArchivedClientWithTheGivenId() {
        when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClientView view = adapter.createWithId(TenantContext.getCurrentTenant(), new CreateClientInput(ID, "Alice", "+237690000001", "a@b.cm", "VIP"));

        var captor = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(captor.capture());
        Client saved = captor.getValue();
        assertThat(saved.id()).isEqualTo(ID);
        assertThat(saved.archived()).isFalse();
        assertThat(saved.createdAt()).isNotNull().isEqualTo(saved.updatedAt());
        assertThat(view).isEqualTo(new ClientView(ID, "Alice", "+237690000001", "a@b.cm", "VIP", false,
                saved.createdAt(), saved.updatedAt()));
    }

    @Test
    void createWithId_invalidPhone_isValidationError_andNothingIsSaved() {
        assertThatThrownBy(() -> adapter.createWithId(TenantContext.getCurrentTenant(), new CreateClientInput(ID, "Bob", "invalid", null, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(clientRepository, never()).save(any());
    }

    @Test
    void update_mapsOntoTheUseCaseCommand() {
        when(updateClient.execute(any())).thenReturn(
                new Client(ID, "Alice B", "+237690000002", null, "n", false, T0, T0));

        ClientView view = adapter.update(TenantContext.getCurrentTenant(), new UpdateClientInput(ID, "Alice B", "+237690000002", null, "n", ACTOR));

        verify(updateClient).execute(new UpdateClientUseCase.UpdateClientCommand(
                ID, "Alice B", "+237690000002", null, "n", ACTOR));
        assertThat(view.name()).isEqualTo("Alice B");
    }

    @Test
    void archive_and_unarchive_delegate() {
        adapter.archive(TENANT, ID, ACTOR);
        adapter.unarchive(TENANT, ID, ACTOR);

        verify(archiveClient).execute(ID, ACTOR, TENANT);
        verify(unarchiveClient).execute(ID, ACTOR, TENANT);
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
