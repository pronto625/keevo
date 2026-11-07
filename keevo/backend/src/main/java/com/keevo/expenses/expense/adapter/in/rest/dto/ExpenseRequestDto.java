package com.keevo.expenses.expense.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.keevo.expenses.expense.application.usecase.ManageExpenseUseCase;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpensePaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Body of {@code POST /expenses} and {@code PUT /expenses/{id}} (Story 20.10), and payload of the
 * {@code CREATE_EXPENSE} / {@code UPDATE_EXPENSE} sync operations (same validation). Bref's rules
 * ({@code CreateExpenseDto}); {@code label} also accepts Bref's name {@code description}.
 *
 * <p>{@code id}: the id chosen by the device (creation only; replayed → the expense already created). {@code storeId}:
 * creation = the store (absent: the member's store / the owner's oldest store); edit = absent → unchanged.
 * A {@code PUT} replaces the whole content: absent optional fields ({@code label}, {@code note},
 * {@code paymentMethod}) are cleared, an absent {@code expenseDate} becomes today (UTC). {@code expectedUpdatedAt}
 * (edit only, optional): the expense changed since → 409 {@code OPTIMISTIC_LOCK}.
 */
public record ExpenseRequestDto(
        UUID id,
        UUID storeId,
        @NotNull(message = "Le montant est requis")
        @DecimalMin(value = "0.01", message = "Le montant doit être d'au moins 0,01")
        @DecimalMax(value = "999999999999", message = "Le montant est trop élevé")
        @Digits(integer = 12, fraction = 2, message = "Le montant accepte au plus 2 décimales")
        BigDecimal amount,
        @NotBlank(message = "Choisissez une catégorie")
        @Pattern(regexp = ExpenseCategory.KEYS_PATTERN, message = "Catégorie de dépense inconnue")
        String category,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "Date de dépense invalide (format attendu AAAA-MM-JJ)")
        String expenseDate,
        @JsonAlias("description")
        @Size(max = 500, message = "Le libellé ne doit pas dépasser 500 caractères")
        String label,
        @Size(max = 1000, message = "La note ne doit pas dépasser 1000 caractères")
        String note,
        @Pattern(regexp = ExpensePaymentMethod.KEYS_PATTERN, message = "Mode de paiement inconnu")
        String paymentMethod,
        Instant expectedUpdatedAt
) {

    public ManageExpenseUseCase.Command toCommand() {
        return new ManageExpenseUseCase.Command(storeId, category, amount, expenseDate, label, note, paymentMethod,
                expectedUpdatedAt);
    }
}
