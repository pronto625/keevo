package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.MarketingConsent;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.contact.ClientCreatedEvent;
import com.keevo.shared.event.catalog.contact.ClientMarketingConsentChangedEvent;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Story 20.2 — store assignment, visibility, duplicate warning and consent rules of the client use cases. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Clients per store — use cases (Story 20.2)")
class ClientsPerStoreUseCasesTest {

    private static final String T = "kv_cps001";
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-01T08:00:00Z");

    @Mock ClientRepository repo;
    @Mock StoreQueryPort storeQuery;
    @Mock ApplicationEventPublisher events;

    ClientStoreScope storeScope;
    ClientFinder finder;

    @BeforeEach
    void setUp() {
        storeScope = new ClientStoreScope(storeQuery);
        finder = new ClientFinder(repo, storeScope);
    }

    private static Client client(UUID store, MarketingConsent consent, boolean archived) {
        return new Client(UUID.randomUUID(), "Awa", "+237699001122", null, null, archived, T0, T0, null, store,
                consent);
    }

    private CreateClientUseCase.CreateClientCommand create(String phone, UUID storeId, ClientAccess access) {
        return new CreateClientUseCase.CreateClientCommand(null, "Awa", phone, null, null, ClientProfileInput.NONE,
                storeId, access, ACTOR, T, true);
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Test
    void employeeCreates_inItsStore_andTheDuplicateOfTheSameStoreIsAWarning() {
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A));
        Client existing = client(A, null, false);
        when(repo.findActiveByStoreAndPhone(A, "+237699001122")).thenReturn(List.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var useCase = new CreateClientUseCase(repo, events, storeScope);

        var result = useCase.execute(create("699001122", null, ClientAccess.member(A)));

        assertThat(result.client().storeId()).isEqualTo(A);
        assertThat(result.client().phone()).isEqualTo("+237699001122");
        assertThat(result.duplicates()).containsExactly(existing);
        verify(repo).save(any());
        verify(events).publishEvent(any(ClientCreatedEvent.class));
    }

    @Test
    void noDuplicate_noWarning_andTheSyncCreateIsNotAudited() {
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A));
        when(repo.findActiveByStoreAndPhone(A, "+237699001122")).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var useCase = new CreateClientUseCase(repo, events, storeScope);
        UUID id = UUID.randomUUID();

        var result = useCase.execute(new CreateClientUseCase.CreateClientCommand(id, "Awa", "+237699001122", null,
                null, null, null, ClientAccess.member(A), ACTOR, T, false));

        assertThat(result.client().id()).isEqualTo(id);
        assertThat(result.duplicates()).isEmpty();
        verifyNoInteractions(events);
    }

    @Test
    void employeeForgingAnotherStore_isForbidden_nothingSaved() {
        var useCase = new CreateClientUseCase(repo, events, storeScope);

        assertThatThrownBy(() -> useCase.execute(create("699001122", B, ClientAccess.member(A))))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repo, never()).save(any());
    }

    @Test
    void ownerWithoutStore_getsTheOldestActiveStore() {
        when(storeQuery.findAllActive(T)).thenReturn(List.of(
                new StoreView(B, "B", StoreType.STORE, null, null, true, T0.plusSeconds(5), T0),
                new StoreView(A, "A", StoreType.STORE, null, null, true, T0, T0)));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var useCase = new CreateClientUseCase(repo, events, storeScope);

        assertThat(useCase.execute(create("699001122", null, ClientAccess.SYSTEM)).client().storeId()).isEqualTo(A);
    }

    @Test
    void ownerWithAnUnknownStore_isAValidationError() {
        when(storeQuery.findById(T, C)).thenReturn(Optional.empty());
        var useCase = new CreateClientUseCase(repo, events, storeScope);

        assertThatThrownBy(() -> useCase.execute(create("699001122", C, ClientAccess.SYSTEM)))
                .satisfies(e -> assertThat(ClientStoreScope.isUnknownStore((DomainException) e)).isTrue());
        verify(repo, never()).save(any());
    }

    // ── visibility (read / update / archive / consent) ──────────────────────

    @Test
    void memberReadingAClientOfAnotherStore_isNotFound_legacyAndSharedAreVisible() {
        Client ofC = client(C, null, false);
        Client ofB = client(B, null, false);
        Client legacy = client(null, null, false);
        when(repo.findById(ofC.id())).thenReturn(Optional.of(ofC));
        when(repo.findById(ofB.id())).thenReturn(Optional.of(ofB));
        when(repo.findById(legacy.id())).thenReturn(Optional.of(legacy));
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A, B));

        assertThatThrownBy(() -> finder.require(ofC.id(), ClientAccess.member(A), T))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.CLIENT_NOT_FOUND));
        assertThat(finder.require(ofB.id(), ClientAccess.member(A), T)).isEqualTo(ofB);
        assertThat(finder.require(legacy.id(), ClientAccess.member(A), T)).isEqualTo(legacy);
        assertThat(finder.require(ofC.id(), ClientAccess.SYSTEM, T)).isEqualTo(ofC);
    }

    @Test
    void archiveOfAnInvisibleClient_isNotFound_nothingArchived() {
        Client ofC = client(C, null, false);
        when(repo.findById(ofC.id())).thenReturn(Optional.of(ofC));
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A));

        assertThatThrownBy(() -> new ArchiveClientUseCase(repo, finder, events)
                .execute(ofC.id(), ACTOR, T, ClientAccess.member(A)))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.CLIENT_NOT_FOUND));
        verify(repo, never()).archive(any());
    }

    @Test
    void onlyTheOwnerMovesAClient_toAnActiveStore() {
        Client ofA = client(A, null, false);
        when(repo.findById(ofA.id())).thenReturn(Optional.of(ofA));
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A));
        when(storeQuery.findById(T, B)).thenReturn(Optional.of(
                new StoreView(B, "B", StoreType.STORE, null, null, true, T0, T0)));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var useCase = new UpdateClientUseCase(repo, finder, storeScope, events);

        assertThatThrownBy(() -> useCase.execute(new UpdateClientUseCase.UpdateClientCommand(ofA.id(), null, null,
                null, null, ACTOR, null, B, ClientAccess.member(A), T)))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        // same store sent by the member: unchanged, accepted
        assertThat(useCase.execute(new UpdateClientUseCase.UpdateClientCommand(ofA.id(), "N", null, null, null,
                ACTOR, null, A, ClientAccess.member(A), T)).storeId()).isEqualTo(A);
        Client moved = useCase.execute(new UpdateClientUseCase.UpdateClientCommand(ofA.id(), null, null, null, null,
                ACTOR, new ClientProfileInput(null, "M1", null, null, null, null, null), B, ClientAccess.SYSTEM, T));
        assertThat(moved.storeId()).isEqualTo(B);
        assertThat(moved.profile().niu()).isEqualTo("M1");
        // the move is audited with the store names
        var captor = ArgumentCaptor.forClass(com.keevo.shared.event.catalog.contact.ClientStoreChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().toStoreId()).isEqualTo(B);
        assertThat(captor.getValue().toStoreName()).isEqualTo("B");
    }

    // ── consent ─────────────────────────────────────────────────────────────

    @Test
    void consentGiven_thenWithdrawn_auditedEachTime() {
        Client c = client(A, null, false);
        when(repo.findById(c.id())).thenReturn(Optional.of(c));
        var useCase = new SetClientMarketingConsentUseCase(repo, finder, events);

        Client given = useCase.execute(c.id(), true, ACTOR, T, ClientAccess.SYSTEM);
        assertThat(given.consent().consentAt()).isNotNull();
        assertThat(given.consent().consentBy()).isEqualTo(ACTOR);
        assertThat(given.marketingEligible()).isTrue();
        assertThat(given.updatedAt()).isAfter(T0);

        when(repo.findById(c.id())).thenReturn(Optional.of(given));
        Client withdrawn = useCase.execute(c.id(), false, ACTOR, T, ClientAccess.SYSTEM);
        assertThat(withdrawn.consent().consentAt()).isNull();
        assertThat(withdrawn.consent().consentBy()).isNull();
        assertThat(withdrawn.marketingEligible()).isFalse();
        // only the consent columns are written (targeted update), never a whole-entity save
        verify(repo).updateConsent(eq(c.id()), eq(given.consent()), any());
        verify(repo).updateConsent(eq(c.id()), eq(withdrawn.consent()), any());
        verify(repo, never()).save(any());

        var captor = ArgumentCaptor.forClass(ClientMarketingConsentChangedEvent.class);
        verify(events, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(ClientMarketingConsentChangedEvent::consent)
                .containsExactly(true, false);
        assertThat(captor.getAllValues().get(0).storeId()).isEqualTo(A);
    }

    @Test
    void optOutWins_teamConsentKeepsTheStop() {
        Instant stop = T0.minusSeconds(60);
        Client c = client(A, new MarketingConsent(null, null, stop), false);
        when(repo.findById(c.id())).thenReturn(Optional.of(c));

        Client after = new SetClientMarketingConsentUseCase(repo, finder, events)
                .execute(c.id(), true, ACTOR, T, ClientAccess.SYSTEM);

        assertThat(after.consent().consentAt()).isNotNull();
        assertThat(after.consent().optOutAt()).isEqualTo(stop);
        assertThat(after.marketingEligible()).isFalse();
    }

    @Test
    void unchangedConsent_isANoOp_withoutAudit() {
        Client c = client(A, new MarketingConsent(T0, ACTOR, null), false);
        when(repo.findById(c.id())).thenReturn(Optional.of(c));

        Client same = new SetClientMarketingConsentUseCase(repo, finder, events)
                .execute(c.id(), true, UUID.randomUUID(), T, ClientAccess.SYSTEM);

        assertThat(same.consent().consentAt()).isEqualTo(T0);
        verify(repo, never()).updateConsent(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void archivedClient_isNeverEligible() {
        assertThat(client(A, new MarketingConsent(T0, ACTOR, null), true).marketingEligible()).isFalse();
        assertThat(client(A, new MarketingConsent(T0, ACTOR, null), false).marketingEligible()).isTrue();
    }

    @Test
    void consentOnAnInvisibleClient_isNotFound() {
        Client ofC = client(C, null, false);
        when(repo.findById(ofC.id())).thenReturn(Optional.of(ofC));
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A));

        assertThatThrownBy(() -> new SetClientMarketingConsentUseCase(repo, finder, events)
                .execute(ofC.id(), true, ACTOR, T, ClientAccess.member(A)))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.CLIENT_NOT_FOUND));
        verify(repo, never()).updateConsent(any(), any(), any());
    }

    // ── duplicates pre-check ────────────────────────────────────────────────

    @Test
    void duplicatesPreCheck_memberForcedToItsStore_ownerPicksOne() {
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A));
        var useCase = new FindDuplicateClientsUseCase(repo, storeScope);
        Client match = client(A, null, false);
        when(repo.findActiveByStoreAndPhone(A, "+237699001122")).thenReturn(List.of(match));
        when(storeQuery.findById(T, A)).thenReturn(Optional.of(
                new StoreView(A, "A", StoreType.STORE, null, null, true, T0, T0)));

        assertThat(useCase.execute("6 99 00 11 22", B, ClientAccess.member(A), T)).containsExactly(match);
        assertThat(useCase.execute("+237699001122", A, ClientAccess.SYSTEM, T)).containsExactly(match);
        assertThatThrownBy(() -> useCase.execute("abc", null, ClientAccess.member(A), T))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void duplicatesPreCheck_ownerWithoutStore_searchesEveryActiveStore() {
        var useCase = new FindDuplicateClientsUseCase(repo, storeScope);
        Client inA = client(A, null, false);
        Client inB = client(B, null, false);
        when(storeQuery.findAllActive(T)).thenReturn(List.of(
                new StoreView(A, "A", StoreType.STORE, null, null, true, T0, T0),
                new StoreView(B, "B", StoreType.STORE, null, null, true, T0, T0)));
        when(repo.findActiveByStoresAndPhone(List.of(A, B), "+237699001122")).thenReturn(List.of(inA, inB));

        assertThat(useCase.execute("699001122", null, ClientAccess.SYSTEM, T)).containsExactly(inA, inB);
        verify(repo, never()).findActiveByStoreAndPhone(any(), any());
    }
}
