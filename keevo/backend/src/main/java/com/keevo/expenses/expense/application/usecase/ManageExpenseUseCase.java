package com.keevo.expenses.expense.application.usecase;

import com.keevo.expenses.expense.application.service.ExpenseAccess;
import com.keevo.expenses.expense.application.service.ExpenseStoreScope;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseContent;
import com.keevo.expenses.expense.domain.port.out.ExpenseRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.10 — records, edits and deletes expenses (REST and sync share it). Bref's content rules
 * ({@link ExpenseContent}); store rules ({@link ExpenseStoreScope}). An expense outside the actor's stores, unknown or
 * deleted → 404 {@code EXPENSE_NOT_FOUND}; a deletion is idempotent and final. Writes return the row as written (no
 * read back).
 */
@Service
public class ManageExpenseUseCase {

    /** French message of a stale edit ({@code OPTIMISTIC_LOCK}). */
    public static final String STALE_EXPENSE =
            "Cette dépense a été modifiée entre-temps : rechargez-la avant de l'enregistrer.";

    static final String ID_USED_ELSEWHERE =
            "Cet identifiant de dépense est déjà utilisé dans une autre boutique : enregistrez une nouvelle dépense.";

    /**
     * The raw input of a creation or an edit, as received (keys and texts validated by {@link ExpenseContent#of}).
     *
     * @param storeId           creation: the store (absent = the actor's / the oldest); edit: absent = unchanged
     * @param expectedUpdatedAt edit only: the {@code updatedAt} the editor last saw (optional)
     * @param defaultDate       creation only: the date of an expense sent without one ({@code null} = today in UTC,
     *                          Bref); an offline creation passes the day it was queued. An edit always keeps the
     *                          current date when none is sent.
     */
    public record Command(UUID storeId, String category, BigDecimal amount, String expenseDate, String label,
                          String note, String paymentMethod, Instant expectedUpdatedAt, LocalDate defaultDate) {

        public Command(UUID storeId, String category, BigDecimal amount, String expenseDate, String label,
                       String note, String paymentMethod, Instant expectedUpdatedAt) {
            this(storeId, category, amount, expenseDate, label, note, paymentMethod, expectedUpdatedAt, null);
        }

        public Command withDefaultDate(LocalDate date) {
            return new Command(storeId, category, amount, expenseDate, label, note, paymentMethod, expectedUpdatedAt,
                    date);
        }
    }

    /** A creation's result: {@code created} false when the id already existed (replay). */
    public record Created(Expense expense, boolean created) {
    }

    private final ExpenseRepository expenses;
    private final ExpenseStoreScope stores;
    private final Clock clock;

    public ManageExpenseUseCase(ExpenseRepository expenses, ExpenseStoreScope stores, Clock clock) {
        this.expenses = expenses;
        this.stores = stores;
        this.clock = clock;
    }

    /**
     * A new expense. {@code requestedId} = the id chosen by the device (optional): an expense already created with it
     * is returned as it is (never a second row) when the actor sees it; deleted → 404 {@code EXPENSE_NOT_FOUND};
     * in a store the actor does not see → {@code FORBIDDEN} (nothing of it disclosed).
     */
    @Transactional
    public Created create(UUID requestedId, Command command, ExpenseAccess access, UUID actorId,
                          String tenantSchema) {
        if (requestedId != null) {
            var existing = expenses.findById(requestedId);
            if (existing.isPresent()) {
                return new Created(replayed(existing.get(), access), false);
            }
        }
        ExpenseContent content = content(command, command.defaultDate());
        UUID storeId = stores.storeForNew(access, command.storeId(), tenantSchema);
        UUID id = requestedId != null ? requestedId : UUID.randomUUID();
        Expense expense = Expense.create(id, storeId, content, actorId, now());
        if (!expenses.insertIfAbsent(expense)) {
            // the same id inserted concurrently: the first one wins, this call returns it
            Expense winner = expenses.findById(id).orElseThrow(ManageExpenseUseCase::notFound);
            return new Created(replayed(winner, access), false);
        }
        return new Created(expense, true);
    }

    /** Replaces the whole content of a live expense the actor sees; an absent date keeps the current one. */
    @Transactional
    public Expense update(UUID id, Command command, ExpenseAccess access, UUID actorId, String tenantSchema) {
        Expense current = live(expenses.findByIdForUpdate(id).orElse(null), access);
        checkNotStale(current, command.expectedUpdatedAt());
        ExpenseContent content = content(command, current.expenseDate());
        UUID storeId = stores.storeForEdit(access, current.storeId(), command.storeId(), tenantSchema);
        Expense updated = current.withContent(content, storeId, actorId, now());
        expenses.update(updated);
        return updated;
    }

    /** Deletes an expense the actor sees; already deleted → nothing (idempotent). Unknown / not visible → 404. */
    @Transactional
    public void delete(UUID id, ExpenseAccess access, UUID actorId) {
        Expense current = expenses.findByIdForUpdate(id).filter(e -> access.sees(e.storeId()))
                .orElseThrow(ManageExpenseUseCase::notFound);
        if (current.deleted()) {
            return;
        }
        expenses.update(current.archived(actorId, now()));
    }

    private ExpenseContent content(Command c, LocalDate defaultDate) {
        return ExpenseContent.of(c.category(), c.amount(), c.expenseDate(), defaultDate, c.label(), c.note(),
                c.paymentMethod(), clock);
    }

    private static Expense live(Expense expense, ExpenseAccess access) {
        if (expense == null || expense.deleted() || !access.sees(expense.storeId())) {
            throw notFound();
        }
        return expense;
    }

    private static Expense replayed(Expense expense, ExpenseAccess access) {
        if (!access.sees(expense.storeId())) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, ID_USED_ELSEWHERE);
        }
        if (expense.deleted()) {
            throw notFound();
        }
        return expense;
    }

    /** Compared at the database precision (microseconds) on the locked row — no extra query. */
    private static void checkNotStale(Expense current, Instant expected) {
        if (expected == null) {
            return;
        }
        Instant stored = current.updatedAt();
        if (stored == null || !stored.truncatedTo(ChronoUnit.MICROS).equals(expected.truncatedTo(ChronoUnit.MICROS))) {
            throw DomainException.userFacing(ErrorCode.OPTIMISTIC_LOCK, STALE_EXPENSE,
                    Map.of("currentUpdatedAt", stored == null ? "" : stored.toString()));
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static DomainException notFound() {
        return DomainException.userFacing(ErrorCode.EXPENSE_NOT_FOUND, "Dépense introuvable");
    }
}
