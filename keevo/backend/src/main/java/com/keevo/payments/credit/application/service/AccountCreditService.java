package com.keevo.payments.credit.application.service;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantView;
import com.keevo.payments.credit.domain.model.CreditMovement;
import com.keevo.payments.credit.domain.model.CreditMovementKind;
import com.keevo.payments.credit.domain.port.out.AccountCreditStore;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.export.KeysetCursor;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.3 — the account credit of a company (Bref's {@code account_credit}, FUS-FR10): what the owner sees in « Mon
 * plan » (balance and history) and what the support (super-admin) adds or removes, with a reason. Using the credit on a
 * payment is the checkout's job (reserved with the request, released when it ends unpaid).
 *
 * <p>Pattern: account (balance row + append-only journal written in the same statement). Cost: a page = one balance
 * read + one keyset query (item bought and actor names joined, no N+1); an adjustment = one statement.
 */
@Service
public class AccountCreditService {

    private static final Logger log = LoggerFactory.getLogger(AccountCreditService.class);

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final long MAX_ADJUSTMENT_XAF = 100_000_000L;
    static final String COMPANY_NOT_FOUND = "Entreprise introuvable";
    static final String CURSOR_INVALID = "Page demandée invalide : rechargez l'historique";
    static final String LIMIT_INVALID = "Le nombre de lignes doit être compris entre 1 et 100";
    static final String AMOUNT_INVALID =
            "Le montant doit être non nul et compris entre -100 000 000 et 100 000 000 FCFA";
    static final String REASON_REQUIRED = "Indiquez la raison de l'ajustement (3 à 300 caractères)";
    static final String NEGATIVE_BALANCE = "Le crédit ne peut pas devenir négatif";
    static final String SUPPORT = "Le support Keevo";
    static final String SOMEONE = "Un membre de l'équipe";
    static final String OWNER = "Le propriétaire";
    private static final List<KeyType> CURSOR = List.of(KeyType.TIMESTAMP, KeyType.UUID);

    /**
     * A credit line as the owner sees it (no id, no code to show; {@code kind} is for the app's logic).
     *
     * @param amountXaf signed: + added, − used
     * @param detail    what the payment bought (« Pro · Mensuel », « Pack 100 SMS »), {@code null} otherwise
     */
    public record CreditLine(String kind, String label, String detail, long amountXaf, long balanceAfterXaf,
                             Instant at) {
    }

    /** @param nextCursor pass it as {@code cursor} for the next page, {@code null} on the last page */
    public record CreditPage(long balanceXaf, List<CreditLine> movements, String nextCursor) {
    }

    /**
     * A credit line as the support sees it.
     *
     * @param byLabel who made it (support member, owner who paid), {@code null} for an automatic movement
     * @param reason  the support's reason of an adjustment
     */
    public record AdminCreditLine(String kind, String label, String detail, long amountXaf, long balanceAfterXaf,
                                  String byLabel, String reason, Instant at) {
    }

    public record AdminCreditPage(long balanceXaf, List<AdminCreditLine> movements, String nextCursor) {
    }

    private final AccountCreditStore store;
    private final TenantQueryPort tenants;

    public AccountCreditService(AccountCreditStore store, TenantQueryPort tenants) {
        this.store = store;
        this.tenants = tenants;
    }

    /** The owner's credit (« Mon plan »). */
    @Transactional(readOnly = true)
    public CreditPage ownerPage(String tenantSchema, String cursor, Integer limit) {
        TenantView tenant = company(tenantSchema);
        Page page = page(tenant.id(), cursor, limit);
        return new CreditPage(store.balance(tenant.id()), page.rows().stream().map(AccountCreditService::line)
                .toList(), page.next());
    }

    /** A company's credit for the support. */
    @Transactional(readOnly = true)
    public AdminCreditPage adminPage(UUID tenantId, String cursor, Integer limit) {
        requireTenant(tenantId);
        Page page = page(tenantId, cursor, limit);
        return new AdminCreditPage(store.balance(tenantId), page.rows().stream()
                .map(AccountCreditService::adminLine).toList(), page.next());
    }

    /**
     * The support adds ({@code amountXaf > 0}) or removes ({@code < 0}) credit, with a reason; journaled with the
     * actor.
     *
     * @throws DomainException 422 on a zero / out-of-range amount or a missing reason, 409 when a removal exceeds
     *         the balance, 404 for an unknown company
     */
    @Transactional
    public AdminCreditPage adjust(UUID tenantId, Long amountXaf, String reason, UUID actorId) {
        requireTenant(tenantId);
        if (amountXaf == null || amountXaf == 0 || amountXaf < -MAX_ADJUSTMENT_XAF || amountXaf > MAX_ADJUSTMENT_XAF) {
            throw invalid("amountXaf", AMOUNT_INVALID);
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 3 || why.length() > 300) {
            throw invalid("reason", REASON_REQUIRED);
        }
        long balance;
        if (amountXaf > 0) {
            balance = store.credit(tenantId, amountXaf, CreditMovementKind.ADMIN_ADJUSTMENT, null, actorId, why);
        } else {
            balance = store.debit(tenantId, -amountXaf, CreditMovementKind.ADMIN_ADJUSTMENT, null, actorId, why)
                    .orElseThrow(() -> DomainException.userFacing(ErrorCode.INVALID_STATE, NEGATIVE_BALANCE + " (solde : "
                            + xaf(store.balance(tenantId)) + ")"));
        }
        log.info("[CREDIT] Support adjustment: tenant={} amountXaf={} balanceXaf={}", tenantId, amountXaf, balance);
        return adminPage(tenantId, null, null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Page(List<CreditMovement> rows, String next) {
    }

    private Page page(UUID tenantId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("limit", LIMIT_INVALID);
        }
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                List<Object> keys = KeysetCursor.decode(CURSOR, cursor.trim());
                beforeAt = (Instant) keys.get(0);
                beforeId = (UUID) keys.get(1);
            } catch (IllegalArgumentException | ClassCastException e) {
                throw invalid("cursor", CURSOR_INVALID);
            }
        }
        List<CreditMovement> rows = store.movements(tenantId, beforeAt, beforeId, size + 1);
        if (rows.size() <= size) {
            return new Page(rows, null);
        }
        List<CreditMovement> page = rows.subList(0, size);
        CreditMovement last = page.get(size - 1);
        return new Page(page, KeysetCursor.encode(CURSOR, List.of(last.at(), last.id())));
    }

    static CreditLine line(CreditMovement m) {
        return new CreditLine(m.kind().name(), m.kind().label(), m.itemLabel(), m.amountXaf(), m.balanceAfterXaf(),
                m.at());
    }

    static AdminCreditLine adminLine(CreditMovement m) {
        String by;
        if (m.actorId() == null) {
            by = null;
        } else if (m.actorName() != null && !m.actorName().isBlank()) {
            by = m.actorName();
        } else {
            by = m.kind() == CreditMovementKind.ADMIN_ADJUSTMENT ? SUPPORT
                    : m.paymentRequestId() != null ? OWNER : SOMEONE; // a payment is started by the owner
        }
        return new AdminCreditLine(m.kind().name(), m.kind().label(), m.itemLabel(), m.amountXaf(),
                m.balanceAfterXaf(), by, m.reason(), m.at());
    }

    private TenantView company(String tenantSchema) {
        return (tenantSchema == null ? java.util.Optional.<TenantView>empty() : tenants.findBySchemaName(tenantSchema))
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.TENANT_NOT_FOUND, COMPANY_NOT_FOUND));
    }

    private void requireTenant(UUID tenantId) {
        if (tenantId == null || !store.tenantExists(tenantId)) {
            throw DomainException.userFacing(ErrorCode.NOT_FOUND, COMPANY_NOT_FOUND);
        }
    }

    /** {@code 3 500 FCFA}. */
    public static String xaf(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return (amount < 0 ? "-" : "") + out + " FCFA";
    }

    private static DomainException invalid(String field, String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }
}
