package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.ImportNote.Kind;
import com.keevo.migration.domain.service.BrefExpenseMapper.MappedExpense;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 24.4 — a Bref expense as a Keevo expense. */
@DisplayName("24.4 — Bref expense mapper")
class BrefExpenseMapperTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-05T09:00:00Z");

    private static BrefExpense expense(String category, String method, String description, String createdBy,
                                       Instant deletedAt) {
        return new BrefExpense(UUID.randomUUID(), OWNER.toString(), SHOP.toString().toUpperCase(), createdBy,
                new BigDecimal("15000.50"), category, description, "2026-03-05", method, T0, T0.plusSeconds(60),
                deletedAt);
    }

    @Test
    @DisplayName("same keys, exact amount, civil date, store, author, description as label; nothing noted")
    void plain() {
        UUID author = UUID.randomUUID();
        MappedExpense m = BrefExpenseMapper.map(expense("rent", "mobile_money", " Loyer ", author.toString(), null),
                OWNER);
        assertThat(m.notes()).isEmpty();
        assertThat(m.expense().category()).isEqualTo("rent");
        assertThat(m.expense().paymentMethod()).isEqualTo("mobile_money");
        assertThat(m.expense().amount()).isEqualByComparingTo("15000.50");
        assertThat(m.expense().expenseDate()).isEqualTo(LocalDate.parse("2026-03-05"));
        assertThat(m.expense().storeId()).isEqualTo(SHOP);
        assertThat(m.expense().createdBy()).isEqualTo(author);
        assertThat(m.expense().label()).isEqualTo("Loyer");
        assertThat(m.expense().archivedAt()).isNull();
        assertThat(m.expense().createdAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("unknown category → other, unknown method dropped, long label cut, deleted archived, unknown author → owner")
    void noted() {
        Instant deleted = T0.plusSeconds(99);
        MappedExpense m = BrefExpenseMapper.map(expense("loisirs", "cheque", "x".repeat(600), "not-a-uuid", deleted),
                OWNER);
        assertThat(m.expense().category()).isEqualTo("other");
        assertThat(m.expense().paymentMethod()).isNull();
        assertThat(m.expense().label()).hasSize(500);
        assertThat(m.expense().archivedAt()).isEqualTo(deleted);
        assertThat(m.expense().createdBy()).isEqualTo(OWNER);
        assertThat(m.notes()).containsExactly(Kind.EXPENSE_CATEGORY_UNKNOWN, Kind.EXPENSE_METHOD_UNKNOWN,
                Kind.EXPENSE_FIELD_SHORTENED, Kind.EXPENSE_DELETED, Kind.AUTHOR_REPLACED);
    }
}
