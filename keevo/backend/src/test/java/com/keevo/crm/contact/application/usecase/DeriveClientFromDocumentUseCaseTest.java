package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.api.DocumentClientInput;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.event.catalog.contact.ClientCreatedEvent;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 20.6 — the CRM client of an issued document: store choice, match, COALESCE fill, creation, refusals. */
@DisplayName("DeriveClientFromDocumentUseCase")
class DeriveClientFromDocumentUseCaseTest {

    private static final String TENANT = "kv_abcdef";
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    private final ClientRepository clients = mock(ClientRepository.class);
    private final StoreQueryPort stores = mock(StoreQueryPort.class);
    private final TransactionLockPort lock = mock(TransactionLockPort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private DeriveClientFromDocumentUseCase useCase;

    private final UUID oldest = UUID.randomUUID();
    private final UUID newer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        useCase = new DeriveClientFromDocumentUseCase(clients, new ClientStoreScope(stores), lock, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        StoreView a = new StoreView(oldest, "A", com.keevo.store.api.StoreType.STORE, null, null, true,
                NOW.minusSeconds(3600), NOW);
        StoreView b = new StoreView(newer, "B", com.keevo.store.api.StoreType.STORE, null, null, true, NOW, NOW);
        when(stores.findAllActive(TENANT)).thenReturn(List.of(b, a));
        when(clients.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static DocumentClientInput awa(String phone) {
        return new DocumentClientInput("Awa", phone, "awa@example.com", "RC-1", "NIU-1", "Akwa");
    }

    @Test
    @DisplayName("OWNER, unknown phone → created in the oldest active store, E.164, audited with the actor")
    void ownerCreatesInOldestStore() {
        when(clients.findDocumentMatch(oldest, "+237699000001")).thenReturn(Optional.empty());

        Optional<UUID> id = useCase.execute(awa("699 00 00 01"), ClientAccess.SYSTEM, ACTOR, TENANT);

        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clients).save(saved.capture());
        assertThat(id).contains(saved.getValue().id());
        assertThat(saved.getValue().storeId()).isEqualTo(oldest);
        assertThat(saved.getValue().phone()).isEqualTo("+237699000001");
        assertThat(saved.getValue().email()).isEqualTo("awa@example.com");
        assertThat(saved.getValue().profile().rccm()).isEqualTo("RC-1");
        assertThat(saved.getValue().profile().niu()).isEqualTo("NIU-1");
        assertThat(saved.getValue().profile().address()).isEqualTo("Akwa");
        assertThat(saved.getValue().consent().consentAt()).isNull();
        verify(lock).lock("crm-document-client:" + TENANT + ":" + oldest + ":+237699000001");
        ArgumentCaptor<ClientCreatedEvent> event = ArgumentCaptor.forClass(ClientCreatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().actorId()).isEqualTo(ACTOR);
        assertThat(event.getValue().clientId()).isEqualTo(saved.getValue().id());
    }

    @Test
    @DisplayName("member → its own store; a known phone is linked and only its empty fields filled (never created)")
    void memberLinksKnownClient() {
        UUID store = UUID.randomUUID();
        UUID known = UUID.randomUUID();
        when(clients.findDocumentMatch(store, "+237699000001")).thenReturn(Optional.of(known));

        Optional<UUID> id = useCase.execute(awa("+237 699 00 00 01"), ClientAccess.member(store), ACTOR, TENANT);

        assertThat(id).contains(known);
        verify(clients).fillMissingFromDocument(known, "awa@example.com", "RC-1", "NIU-1", NOW);
        verify(clients, never()).save(any());
        verifyNoInteractions(events, stores);
    }

    @Test
    @DisplayName("unparseable phone, member without store → nothing derived, no exception, no lock")
    void refusals() {
        assertThat(useCase.execute(awa("12"), ClientAccess.SYSTEM, ACTOR, TENANT)).isEmpty();
        assertThat(useCase.execute(awa(null), ClientAccess.SYSTEM, ACTOR, TENANT)).isEmpty();
        assertThat(useCase.execute(awa("699000001"), ClientAccess.member(null), ACTOR, TENANT)).isEmpty();
        verify(lock, never()).lock(anyString());
        verify(clients, never()).save(any());
    }

    @Test
    @DisplayName("no name to create with, invalid email and over-long codes are not copied (never a failure)")
    void defensiveCopies() {
        when(clients.findDocumentMatch(oldest, "+237699000001")).thenReturn(Optional.empty());
        assertThat(useCase.execute(new DocumentClientInput(" ", "699000001", null, null, null, null),
                ClientAccess.SYSTEM, ACTOR, TENANT)).isEmpty();

        Optional<UUID> id = useCase.execute(new DocumentClientInput("Awa", "699000001", "pas-un-email",
                "R".repeat(51), null, null), ClientAccess.SYSTEM, ACTOR, TENANT);
        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clients).save(saved.capture());
        assertThat(id).isPresent();
        assertThat(saved.getValue().email()).isNull();
        assertThat(saved.getValue().profile().rccm()).isNull();
    }
}
