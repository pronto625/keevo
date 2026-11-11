package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.SourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 24.4 — the blocking cases of documents and expenses (nothing is written for the owner). */
@DisplayName("24.4 — Bref documents and expenses validation")
class BrefSalesValidatorTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID SHOP = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00Z");

    private static BrefInvoice invoice(UUID id, String shopId, String type, String status, String subtotal,
                                       String discount, String total, String token, List<BrefInvoiceItem> items,
                                       List<BrefInvoicePayment> payments) {
        return new BrefInvoice(id, OWNER.toString(), shopId, "Client", null, null, null, null, null, null, null,
                new BigDecimal(subtotal), BigDecimal.ZERO, new BigDecimal(discount), BigDecimal.ZERO,
                new BigDecimal(total), status, null, BigDecimal.ZERO, null, null, "2026-03-01", token, null, null,
                null, null, type, null, T0, T0, null, items, payments);
    }

    private static BrefInvoice ok(UUID id) {
        return invoice(id, SHOP.toString().toUpperCase(), "sale", "pending", "100", "0", "100", null,
                List.of(new BrefInvoiceItem(UUID.randomUUID(), id, "R", "Riz", null, BigDecimal.ONE,
                        new BigDecimal("100"), new BigDecimal("100"))), List.of());
    }

    private static BrefExpense expense(UUID id, String shopId, String amount, String date) {
        return new BrefExpense(id, OWNER.toString(), shopId, OWNER.toString(), new BigDecimal(amount), "rent", null,
                date, null, T0, T0, null);
    }

    private static List<BlockedCase> validate(List<BrefInvoice> invoices, List<BrefExpense> expenses) {
        BrefAccount owner = new BrefAccount(OWNER, "owner", null, false, "+237600000001", true, null, null, null,
                null, null, true, false, "email", null, T0, T0, null);
        BrefShop shop = new BrefShop(SHOP, OWNER, "B", "A", "other", null, null, null, null, null, "CM", true, T0, T0,
                null, null);
        BrefSnapshot s = new BrefSnapshot(owner, List.of(shop), List.of(), List.of(), List.of(), invoices, expenses,
                SourceKind.DATABASE);
        List<BlockedCase> cases = new ArrayList<>();
        BrefSalesValidator.validate(s, Set.of(SHOP.toString()), cases);
        return cases;
    }

    @Test
    @DisplayName("valid documents and expenses (shop id in any case): no case")
    void valid() {
        assertThat(validate(List.of(ok(UUID.randomUUID())),
                List.of(expense(UUID.randomUUID(), " " + SHOP + " ", "0.01", "2026-03-01")))).isEmpty();
    }

    @Test
    @DisplayName("documents: other shop, duplicate, unknown type/status, negative amount, discount above subtotal, bad quantity, foreign or duplicate line/payment, shared token")
    void documentCases() {
        UUID id = UUID.randomUUID();
        String token = UUID.randomUUID().toString();
        BrefInvoice good = ok(id);
        List<BrefInvoice> invoices = List.of(
                invoice(UUID.randomUUID(), UUID.randomUUID().toString(), "sale", "pending", "1", "0", "1", null,
                        List.of(), List.of()),
                good, good,
                invoice(UUID.randomUUID(), SHOP.toString(), "credit", "pending", "1", "0", "1", null, List.of(),
                        List.of()),
                invoice(UUID.randomUUID(), SHOP.toString(), "sale", "pending", "-1", "0", "1", null, List.of(),
                        List.of()),
                invoice(UUID.randomUUID(), SHOP.toString(), "sale", "pending", "10", "20", "0", token, List.of(),
                        List.of()),
                invoice(UUID.randomUUID(), SHOP.toString(), "sale", "pending", "10", "0", "10", token,
                        List.of(new BrefInvoiceItem(UUID.randomUUID(), id, "R", "Riz", null, BigDecimal.ZERO,
                                BigDecimal.TEN, BigDecimal.TEN)),
                        List.of(new BrefInvoicePayment(UUID.randomUUID(), id, new BigDecimal("-5"), "2026-03-01",
                                false, null, null, T0, T0))));
        List<String> labels = validate(invoices, List.of()).stream().map(BlockedCase::label).toList();
        assertThat(validate(invoices, List.of())).allMatch(c -> c.code().equals("DOCUMENT_INVALID")
                && c.sourceTable().equals("invoice"));
        assertThat(labels).anyMatch(l -> l.contains("autre boutique ou en double"))
                .anyMatch(l -> l.contains("type ou statut"))
                .anyMatch(l -> l.contains("montant négatif"))
                .anyMatch(l -> l.contains("remise supérieure"))
                .anyMatch(l -> l.contains("lien public partagé"))
                .anyMatch(l -> l.contains("quantité de ligne"))
                .anyMatch(l -> l.contains("ligne en double ou d'un autre document"))
                .anyMatch(l -> l.contains("paiement en double ou d'un autre document"))
                .anyMatch(l -> l.contains("montant de paiement"));
    }

    @Test
    @DisplayName("expenses: other shop, duplicate, below 0.01, no valid date")
    void expenseCases() {
        UUID id = UUID.randomUUID();
        BrefExpense dup = expense(id, SHOP.toString(), "10", "2026-03-01");
        List<BlockedCase> cases = validate(List.of(), List.of(
                expense(UUID.randomUUID(), UUID.randomUUID().toString(), "10", "2026-03-01"), dup, dup,
                expense(UUID.randomUUID(), SHOP.toString(), "0", "2026-03-01"),
                expense(UUID.randomUUID(), SHOP.toString(), "10", "2026-13-01"),
                expense(UUID.randomUUID(), SHOP.toString(), "1000000000000", "2026-03-01"),
                expense(UUID.randomUUID(), SHOP.toString(), "1.005", "2026-03-01")));
        assertThat(cases).hasSize(6).allMatch(c -> c.code().equals("EXPENSE_INVALID")
                && c.sourceTable().equals("expense"));
    }
}
