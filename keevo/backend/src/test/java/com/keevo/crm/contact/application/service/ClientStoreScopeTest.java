package com.keevo.crm.contact.application.service;

import com.keevo.crm.contact.domain.service.ClientVisibility;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientStoreScope — store of a new client and visible scope (Story 20.2)")
class ClientStoreScopeTest {

    private static final String T = "kv_scope1";
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Mock StoreQueryPort storeQuery;
    ClientStoreScope scope;

    @BeforeEach
    void setUp() {
        scope = new ClientStoreScope(storeQuery);
    }

    private static StoreView view(UUID id, boolean active, Instant createdAt) {
        return new StoreView(id, "S", StoreType.STORE, null, null, active, createdAt, createdAt);
    }

    @Test
    void member_alwaysGetsItsStore() {
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A));
        assertThat(scope.storeForNewClient(ClientAccess.member(A), null, T)).isEqualTo(A);
        assertThat(scope.storeForNewClient(ClientAccess.member(A), A, T)).isEqualTo(A);
    }

    @Test
    void member_ofADeactivatedStore_isForbidden() {
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of()); // inactive / unknown store
        assertThatThrownBy(() -> scope.storeForNewClient(ClientAccess.member(A), null, T))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void ownerFilteringOnAnUnknownOrInactiveStore_isAValidationErrorOnStoreId() {
        when(storeQuery.clientVisibleStoreIds(T, B)).thenReturn(Set.of());
        assertThatThrownBy(() -> scope.readScope(ClientAccess.SYSTEM, B, T))
                .satisfies(e -> assertThat(ClientStoreScope.isUnknownStore((DomainException) e)).isTrue());
    }

    @Test
    void member_forgingAnotherStore_isForbidden() {
        assertThatThrownBy(() -> scope.storeForNewClient(ClientAccess.member(A), B, T))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> scope.storeForNewClient(ClientAccess.member(null), null, T))
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void owner_withoutStore_getsTheOldestActiveStore() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        when(storeQuery.findAllActive(T)).thenReturn(List.of(view(B, true, t0.plusSeconds(60)), view(A, true, t0)));
        assertThat(scope.storeForNewClient(ClientAccess.SYSTEM, null, T)).isEqualTo(A);
    }

    @Test
    void owner_withoutAnyStore_getsNone() {
        when(storeQuery.findAllActive(T)).thenReturn(List.of());
        assertThat(scope.storeForNewClient(ClientAccess.SYSTEM, null, T)).isNull();
    }

    @Test
    void owner_withAStore_mustBeActive() {
        Instant t0 = Instant.now();
        when(storeQuery.findById(T, A)).thenReturn(Optional.of(view(A, true, t0)));
        when(storeQuery.findById(T, B)).thenReturn(Optional.of(view(B, false, t0)));
        UUID unknown = UUID.randomUUID();
        when(storeQuery.findById(T, unknown)).thenReturn(Optional.empty());

        assertThat(scope.storeForNewClient(ClientAccess.SYSTEM, A, T)).isEqualTo(A);
        for (UUID bad : List.of(B, unknown)) {
            assertThatThrownBy(() -> scope.storeForNewClient(ClientAccess.SYSTEM, bad, T))
                    .isInstanceOf(DomainException.class)
                    .satisfies(e -> {
                        assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(ClientStoreScope.isUnknownStore((DomainException) e)).isTrue();
                    });
        }
    }

    @Test
    void readScope_oneStoresQueryForAMemberOrAFilteringOwner_noneForTheOwner() {
        when(storeQuery.clientVisibleStoreIds(T, A)).thenReturn(Set.of(A, B));

        assertThat(scope.readScope(ClientAccess.SYSTEM, null, T)).isEqualTo(ClientVisibility.Scope.UNRESTRICTED);
        assertThat(scope.readScope(ClientAccess.member(A), null, T))
                .isEqualTo(ClientVisibility.Scope.stores(Set.of(A, B)));
        assertThat(scope.readScope(ClientAccess.SYSTEM, A, T)).isEqualTo(ClientVisibility.Scope.stores(Set.of(A, B)));
        assertThat(scope.readScope(ClientAccess.member(null), null, T))
                .isEqualTo(ClientVisibility.Scope.stores(Set.of()));
        verify(storeQuery, times(2)).clientVisibleStoreIds(T, A);
    }
}
