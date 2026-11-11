package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BlockedCase.Kind;
import com.keevo.migration.domain.model.BrefExpense;
import com.keevo.migration.domain.model.BrefInvoice;
import com.keevo.migration.domain.model.BrefInvoiceItem;
import com.keevo.migration.domain.model.BrefInvoicePayment;
import com.keevo.migration.domain.model.BrefSnapshot;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_EXPENSE;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_INVOICE;

/**
 * Story 24.4 — the documents and expenses an owner cannot be imported with (pure, before any write): a row of another
 * shop or present twice, a value Keevo cannot store as Bref has it (negative or too large amount, a discount above the
 * subtotal, a line quantity that is not positive with at most 3 decimals, an expense below 0.01 or with more than 2
 * decimals, an unreadable expense date), an unknown document type or status, a public token used twice. Anything else
 * is imported and noted ({@link BrefDocumentPlanner}). O(n) over documents, lines, payments and expenses.
 */
public final class BrefSalesValidator {

    private static final Set<String> TYPES = Set.of("sale", "quote");
    private static final Set<String> STATUSES = Set.of("pending", "partial", "overdue", "paid", "cancelled");
    private static final BigDecimal MIN_EXPENSE = new BigDecimal("0.01");

    private BrefSalesValidator() {
    }

    /** @param shopIds the owner's shop ids (lower-case text) */
    public static void validate(BrefSnapshot snapshot, Set<String> shopIds, List<BlockedCase> cases) {
        Set<UUID> invoiceIds = new HashSet<>();
        Set<UUID> itemIds = new HashSet<>();
        Set<UUID> paymentIds = new HashSet<>();
        Set<UUID> tokens = new HashSet<>();
        for (BrefInvoice i : snapshot.invoices()) {
            if (!invoiceIds.add(i.id()) || !ownShop(i.shopId(), shopIds)) {
                cases.add(invalid("document d'une autre boutique ou en double", i.id()));
            }
            if (!TYPES.contains(lower(i.type())) || !STATUSES.contains(lower(i.status()))) {
                cases.add(invalid("type ou statut de document inconnu", i.id()));
            }
            if (!BrefDocumentPlanner.amountInRange(i.subtotal()) || !BrefDocumentPlanner.amountInRange(i.tax())
                    || !BrefDocumentPlanner.amountInRange(i.discount())
                    || !BrefDocumentPlanner.amountInRange(i.deliveryFee())
                    || !BrefDocumentPlanner.amountInRange(i.total())
                    || !BrefDocumentPlanner.amountInRange(i.initialPayment())) {
                cases.add(invalid("montant négatif ou trop grand", i.id()));
            } else if (i.discount().compareTo(i.subtotal()) > 0) {
                cases.add(invalid("remise supérieure au sous-total", i.id()));
            }
            UUID token = BrefMappings.uuidOrNull(i.publicToken());
            if (token != null && !i.deleted() && !tokens.add(token)) {
                cases.add(invalid("lien public partagé par deux documents", i.id()));
            }
            for (BrefInvoiceItem item : i.items()) {
                if (!itemIds.add(item.id()) || !item.invoiceId().equals(i.id())) {
                    cases.add(invalid("ligne en double ou d'un autre document", i.id()));
                }
                BigDecimal q = item.quantity();
                if (q.signum() <= 0 || q.stripTrailingZeros().scale() > 3
                        || q.compareTo(BrefDocumentPlanner.MAX_QUANTITY) > 0) {
                    cases.add(invalid("quantité de ligne nulle, négative ou invalide", i.id()));
                }
                if (!BrefDocumentPlanner.amountInRange(item.unitPrice())
                        || !BrefDocumentPlanner.amountInRange(item.total())) {
                    cases.add(invalid("montant de ligne négatif ou trop grand", i.id()));
                }
            }
            for (BrefInvoicePayment p : i.payments()) {
                if (!paymentIds.add(p.id()) || !p.invoiceId().equals(i.id())) {
                    cases.add(invalid("paiement en double ou d'un autre document", i.id()));
                }
                if (!BrefDocumentPlanner.amountInRange(p.amount())) {
                    cases.add(invalid("montant de paiement négatif ou trop grand", i.id()));
                }
            }
        }
        Set<UUID> expenseIds = new HashSet<>();
        for (BrefExpense e : snapshot.expenses()) {
            if (!expenseIds.add(e.id()) || !ownShop(e.shopId(), shopIds)) {
                cases.add(BlockedCase.of(Kind.EXPENSE_INVALID, "dépense d'une autre boutique ou en double",
                        BREF_EXPENSE, e.id()));
            }
            BigDecimal a = e.amount();
            if (a.compareTo(MIN_EXPENSE) < 0 || a.compareTo(BrefDocumentPlanner.MAX_AMOUNT) > 0
                    || a.stripTrailingZeros().scale() > 2) {
                cases.add(BlockedCase.of(Kind.EXPENSE_INVALID,
                        "montant inférieur à 0,01, trop grand ou avec plus de 2 décimales", BREF_EXPENSE, e.id()));
            }
            if (BrefDocumentPlanner.civilDate(e.expenseDate()) == null) {
                cases.add(BlockedCase.of(Kind.EXPENSE_INVALID, "date invalide", BREF_EXPENSE, e.id()));
            }
        }
    }

    private static BlockedCase invalid(String detail, UUID invoiceId) {
        return BlockedCase.of(Kind.DOCUMENT_INVALID, detail, BREF_INVOICE, invoiceId);
    }

    private static String lower(String v) {
        return v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean ownShop(String shopId, Set<String> shopIds) {
        UUID id = BrefMappings.uuidOrNull(shopId);
        return id != null && shopIds.contains(id.toString());
    }
}
