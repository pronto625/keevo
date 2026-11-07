package com.keevo.expenses.expense.application;

import com.keevo.expenses.expense.application.service.ExpenseAccess;
import com.keevo.expenses.expense.application.service.ExpenseStoreScope;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreType;
import com.keevo.store.api.StoreView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExpenseStoreScope — expenses per store (Story 20.10, ADR-08)")
class ExpenseStoreScopeTest {

    private static final String T = "kv_test01";
    private final StoreQueryPort stores = mock(StoreQueryPort.class);
    private final ExpenseStoreScope scope = new ExpenseStoreScope(stores);
    private final UUID older = UUID.randomUUID();
    private final UUID newer = UUID.randomUUID();
    private final UUID closed = UUID.randomUUID();

    private static StoreView store(UUID id, boolean active, Instant createdAt) {
        return new StoreView(id, "Boutique", StoreType.STORE, null, null, active, createdAt, createdAt);
    }

    private void tenantStores() {
        StoreView a = store(older, true, Instant.parse("2026-01-01T00:00:00Z"));
        StoreView b = store(newer, true, Instant.parse("2026-06-01T00:00:00Z"));
        StoreView c = store(closed, false, Instant.parse("2025-01-01T00:00:00Z"));
        when(stores.findAllActive(T)).thenReturn(List.of(b, a));
        when(stores.findById(T, older)).thenReturn(Optional.of(a));
        when(stores.findById(T, newer)).thenReturn(Optional.of(b));
        when(stores.findById(T, closed)).thenReturn(Optional.of(c));
    }

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("list: owner all or one known store (inactive included); member their store (filter ignored), none "
            + "without store")
    void listScope() {
        tenantStores();
        assertThat(scope.listScope(ExpenseAccess.OWNER, null, T)).isEqualTo(ExpenseStoreScope.ListScope.ALL);
        assertThat(scope.listScope(ExpenseAccess.OWNER, closed, T).storeId()).isEqualTo(closed);
        refused(() -> scope.listScope(ExpenseAccess.OWNER, UUID.randomUUID(), T), ErrorCode.VALIDATION_ERROR);
        assertThat(scope.listScope(ExpenseAccess.member(newer), older, T).storeId()).isEqualTo(newer);
        assertThat(scope.listScope(ExpenseAccess.member(null), null, T).none()).isTrue();
    }

    @Test
    @DisplayName("new expense: owner → requested active store or the oldest active one; member → their active store only")
    void storeForNew() {
        tenantStores();
        assertThat(scope.storeForNew(ExpenseAccess.OWNER, null, T)).isEqualTo(older);
        assertThat(scope.storeForNew(ExpenseAccess.OWNER, newer, T)).isEqualTo(newer);
        refused(() -> scope.storeForNew(ExpenseAccess.OWNER, closed, T), ErrorCode.VALIDATION_ERROR);
        assertThat(scope.storeForNew(ExpenseAccess.member(newer), null, T)).isEqualTo(newer);
        refused(() -> scope.storeForNew(ExpenseAccess.member(newer), older, T), ErrorCode.FORBIDDEN);
        refused(() -> scope.storeForNew(ExpenseAccess.member(closed), null, T), ErrorCode.FORBIDDEN);
        refused(() -> scope.storeForNew(ExpenseAccess.member(null), null, T), ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("edit: unchanged store costs no query; only the owner moves an expense, to an active store")
    void storeForEdit() {
        assertThat(scope.storeForEdit(ExpenseAccess.member(newer), newer, null, T)).isEqualTo(newer);
        assertThat(scope.storeForEdit(ExpenseAccess.member(newer), newer, newer, T)).isEqualTo(newer);
        verifyNoInteractions(stores);
        tenantStores();
        refused(() -> scope.storeForEdit(ExpenseAccess.member(newer), newer, older, T), ErrorCode.FORBIDDEN);
        assertThat(scope.storeForEdit(ExpenseAccess.OWNER, newer, older, T)).isEqualTo(older);
        refused(() -> scope.storeForEdit(ExpenseAccess.OWNER, newer, closed, T), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("an owner without any active store cannot record (French reason)")
    void noStore() {
        when(stores.findAllActive(T)).thenReturn(List.of());
        refused(() -> scope.storeForNew(ExpenseAccess.OWNER, null, T), ErrorCode.VALIDATION_ERROR);
    }
}
