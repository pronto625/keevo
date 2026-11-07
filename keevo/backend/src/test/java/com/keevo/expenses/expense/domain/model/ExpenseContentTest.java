package com.keevo.expenses.expense.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExpenseContent — Bref's expense rules (Story 20.10)")
class ExpenseContentTest {

    /** 2026-09-20T12:00Z, Bref's expense-date.spec.ts clock. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

    private static ExpenseContent of(String category, String amount, String date) {
        return ExpenseContent.of(category, amount == null ? null : new BigDecimal(amount), date, null, null, null,
                CLOCK);
    }

    private static void refused(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(e.getUserMessage()).isEqualTo(message);
        });
    }

    @Test
    @DisplayName("the 10 Bref categories and 5 payment methods, by key, with the Bref app's French labels")
    void bref_keys() {
        assertThat(Arrays.stream(ExpenseCategory.values()).map(ExpenseCategory::key)).containsExactly(
                "goods_purchase", "rent", "salaries", "transport", "utilities", "telecom", "marketing", "taxes",
                "maintenance", "other");
        assertThat(ExpenseCategory.UTILITIES.label()).isEqualTo("Eau et électricité");
        assertThat(ExpenseCategory.TELECOM.label()).isEqualTo("Internet et téléphone");
        assertThat(String.join("|", Arrays.stream(ExpenseCategory.values()).map(ExpenseCategory::key).toList()))
                .isEqualTo(ExpenseCategory.KEYS_PATTERN);
        assertThat(Arrays.stream(ExpensePaymentMethod.values()).map(ExpensePaymentMethod::key)).containsExactly(
                "cash", "mobile_money", "bank_transfer", "card", "other");
        assertThat(String.join("|", Arrays.stream(ExpensePaymentMethod.values()).map(ExpensePaymentMethod::key)
                .toList())).isEqualTo(ExpensePaymentMethod.KEYS_PATTERN);
        assertThat(ExpenseCategory.fromKey("RENT")).isEmpty(); // exact keys, like Bref's IsIn
    }

    @Test
    @DisplayName("a valid expense: amount kept at 2 decimals, texts trimmed, blank = none")
    void valid() {
        ExpenseContent c = ExpenseContent.of("rent", new BigDecimal("150000"), "2026-09-01", "  Loyer  ", "   ",
                "mobile_money", CLOCK);
        assertThat(c.category()).isEqualTo(ExpenseCategory.RENT);
        assertThat(c.amount()).isEqualByComparingTo("150000").hasScaleOf(2);
        assertThat(c.expenseDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(c.label()).isEqualTo("Loyer");
        assertThat(c.note()).isNull();
        assertThat(c.paymentMethod()).isEqualTo(ExpensePaymentMethod.MOBILE_MONEY);
    }

    @Test
    @DisplayName("Bref amount bounds: 0.01 … 999 999 999 999, at most 2 decimals")
    void amount_bounds() {
        assertThat(of("other", "0.01", null).amount()).isEqualByComparingTo("0.01");
        assertThat(of("other", "999999999999", null).amount()).isEqualByComparingTo("999999999999");
        assertThat(of("other", "12.50", null).amount()).isEqualByComparingTo("12.5");
        refused(() -> of("other", "0", null), "Le montant doit être d'au moins 0,01");
        refused(() -> of("other", "-5", null), "Le montant doit être d'au moins 0,01");
        refused(() -> of("other", "1000000000000", null), "Le montant est trop élevé");
        refused(() -> of("other", "10.123", null), "Le montant accepte au plus 2 décimales");
        refused(() -> of("other", null, null), "Le montant est requis");
    }

    @Test
    @DisplayName("Bref isIsoDate: real calendar dates only")
    void iso_dates() {
        Clock in2028 = Clock.fixed(Instant.parse("2028-03-01T12:00:00Z"), ZoneOffset.UTC);
        assertThat(ExpenseContent.of("other", BigDecimal.ONE, "2028-02-29", null, null, null, in2028).expenseDate())
                .isEqualTo(LocalDate.of(2028, 2, 29));
        for (String bad : new String[]{"2026-02-31", "2027-02-29", "20/09/2026", "2026-9-2", ""}) {
            refused(() -> of("other", "1", bad), "Date de dépense invalide (format attendu AAAA-MM-JJ)");
        }
    }

    @Test
    @DisplayName("Bref isTooFarInFuture: today and tomorrow (UTC) accepted, later refused; no lower bound")
    void future_dates() {
        assertThat(of("other", "1", "2026-09-20").expenseDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(of("other", "1", "2026-09-21").expenseDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(of("other", "1", "2001-01-01").expenseDate()).isEqualTo(LocalDate.of(2001, 1, 1));
        refused(() -> of("other", "1", "2026-09-22"), "La date de la dépense ne peut pas dépasser demain");
        refused(() -> of("other", "1", "2027-09-20"), "La date de la dépense ne peut pas dépasser demain");
    }

    @Test
    @DisplayName("Bref todayIso: an absent date is today in UTC")
    void default_date() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-09-20T23:30:00Z"), ZoneOffset.UTC);
        assertThat(ExpenseContent.of("other", BigDecimal.ONE, null, null, null, null, lateUtc).expenseDate())
                .isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"food", "Rent", ""})
    @DisplayName("an unknown category is refused")
    void unknown_category(String key) {
        refused(() -> of(key, "1", null), "Catégorie de dépense inconnue");
    }

    @Test
    @DisplayName("unknown payment method, texts too long → refused")
    void other_refusals() {
        refused(() -> ExpenseContent.of("other", BigDecimal.ONE, null, null, null, "cheque", CLOCK),
                "Mode de paiement inconnu");
        refused(() -> ExpenseContent.of("other", BigDecimal.ONE, null, "x".repeat(501), null, null, CLOCK),
                "Le libellé ne doit pas dépasser 500 caractères");
        refused(() -> ExpenseContent.of("other", BigDecimal.ONE, null, null, "x".repeat(1001), null, CLOCK),
                "La note ne doit pas dépasser 1000 caractères");
    }

    @Test
    @DisplayName("sorts: Bref keys, unknown → recent; cursor round-trip bound to its sort")
    void sorts_and_cursor() {
        assertThat(ExpenseSort.fromKey(null)).isEqualTo(ExpenseSort.RECENT);
        assertThat(ExpenseSort.fromKey("whatever")).isEqualTo(ExpenseSort.RECENT);
        assertThat(ExpenseSort.fromKey("amount_asc")).isEqualTo(ExpenseSort.AMOUNT_ASC);
        Expense e = Expense.create(UUID.randomUUID(), UUID.randomUUID(), of("rent", "12.5", "2026-09-01"),
                UUID.randomUUID(), Instant.parse("2026-09-01T10:00:00.123456Z"));
        for (ExpenseSort sort : ExpenseSort.values()) {
            ExpenseCursor c = ExpenseCursor.after(sort, e);
            assertThat(ExpenseCursor.decode(c.encode(), sort)).isEqualTo(c);
        }
        String recent = ExpenseCursor.after(ExpenseSort.RECENT, e).encode();
        assertThatThrownBy(() -> ExpenseCursor.decode(recent, ExpenseSort.AMOUNT_DESC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpenseCursor.decode("not-a-cursor", ExpenseSort.RECENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
