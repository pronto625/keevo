package com.keevo.expenses.expense.application;

import com.keevo.expenses.expense.application.service.ExpenseAccess;
import com.keevo.expenses.expense.application.service.ExpenseStoreScope;
import com.keevo.expenses.expense.application.usecase.ExpenseQueryUseCase;
import com.keevo.expenses.expense.application.usecase.ManageExpenseUseCase;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseContent;
import com.keevo.expenses.expense.domain.model.ExpenseSort;
import com.keevo.expenses.expense.domain.port.out.ExpenseRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Expense use cases — replays, manager without store, edit date (Story 20.10)")
class ExpenseUseCasesTest {

    private static final String T = "kv_test01";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private final ExpenseRepository repository = mock(ExpenseRepository.class);
    private final StoreQueryPort stores = mock(StoreQueryPort.class);
    private final ExpenseStoreScope scope = new ExpenseStoreScope(stores);
    private final ManageExpenseUseCase manage = new ManageExpenseUseCase(repository, scope, CLOCK);
    private final ExpenseQueryUseCase queries = new ExpenseQueryUseCase(repository, scope);
    private final UUID storeA = UUID.randomUUID();

    private Expense expense(UUID id) {
        return Expense.create(id, storeA, ExpenseContent.of("rent", BigDecimal.TEN, "2026-10-15", null, null, null,
                CLOCK), UUID.randomUUID(), Instant.parse("2026-10-15T08:00:00Z"));
    }

    private static ManageExpenseUseCase.Command command(String date) {
        return new ManageExpenseUseCase.Command(null, "rent", BigDecimal.ONE, date, null, null, null, null);
    }

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("a manager without store: empty list with count 0 (no query), creation refused")
    void memberWithoutStore() {
        ExpenseQueryUseCase.Page page = queries.list(new ExpenseQueryUseCase.ListQuery(null, null, null, null, null,
                ExpenseSort.RECENT, null, 20), ExpenseAccess.member(null), T);
        assertThat(page.items()).isEmpty();
        assertThat(page.totals().count()).isZero();
        verifyNoInteractions(repository);
        refused(() -> manage.create(null, command(null), ExpenseAccess.member(null), UUID.randomUUID(), T),
                ErrorCode.FORBIDDEN);
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("replays: deleted id → EXPENSE_NOT_FOUND; another store's id → FORBIDDEN (nothing disclosed)")
    void replays() {
        UUID id = UUID.randomUUID();
        Expense deleted = expense(id).archived(UUID.randomUUID(), Instant.parse("2026-10-16T08:00:00Z"));
        when(repository.findById(id)).thenReturn(Optional.of(deleted));
        refused(() -> manage.create(id, command(null), ExpenseAccess.OWNER, UUID.randomUUID(), T),
                ErrorCode.EXPENSE_NOT_FOUND);
        when(repository.findById(id)).thenReturn(Optional.of(expense(id)));
        assertThatThrownBy(() -> manage.create(id, command(null), ExpenseAccess.member(UUID.randomUUID()),
                UUID.randomUUID(), T)).isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(e.getUserMessage()).doesNotContain(id.toString()).doesNotContain("10");
                });
        ManageExpenseUseCase.Created replay = manage.create(id, command(null), ExpenseAccess.OWNER, UUID.randomUUID(), T);
        assertThat(replay.created()).isFalse();
        verify(repository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("an edit without date keeps the current date, never today; the row returned is the one written")
    void editKeepsDate() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(expense(id)));
        Expense updated = manage.update(id, command(null), ExpenseAccess.OWNER, UUID.randomUUID(), T);
        assertThat(updated.expenseDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(updated.amount()).isEqualByComparingTo("1");
        verify(repository).update(updated);
        verify(repository, never()).findById(any());
    }
}
