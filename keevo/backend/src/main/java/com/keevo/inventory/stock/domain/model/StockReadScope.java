package com.keevo.inventory.stock.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.UUID;

/**
 * Story 16.13 (reprise 10/11/2026) — which stores' stock a reader may see. Resolved once by the inbound adapter (REST
 * controller, AI tool) from the authenticated actor and carried by the query down to the repository; the application
 * services and SQL never read the security context.
 *
 * <ul>
 *   <li>{@link #ALL}: the OWNER — every store of the tenant.</li>
 *   <li>{@link #store(UUID)}: a store member (MANAGER, EMPLOYEE, VIEWER) — only their assigned store; a member without
 *       a store ({@code storeId == null}) sees no store at all (fail closed, never another store).</li>
 * </ul>
 *
 * <p>Pure Java (Value Object).
 *
 * @param restricted true for a store member (or an unknown actor — fail closed)
 * @param storeId    the member's store, null when none (or unrestricted)
 */
public record StockReadScope(boolean restricted, UUID storeId) {

    /** The owner's view: every store. */
    public static final StockReadScope ALL = new StockReadScope(false, null);

    /** Fail-closed view: no store at all. */
    public static final StockReadScope NONE = new StockReadScope(true, null);

    /** User-facing refusal for a store outside the reader's scope (same text as the 21.8 AI tool). */
    public static final String OTHER_STORE_MESSAGE = "Vous ne pouvez consulter que le stock de votre boutique.";

    /** A store member's view; {@code storeId} null = member without a store (sees nothing). */
    public static StockReadScope store(UUID storeId) {
        return new StockReadScope(true, storeId);
    }

    /** Whether {@code store} is readable in this scope. */
    public boolean allows(UUID store) {
        return !restricted || (storeId != null && storeId.equals(store));
    }

    /** True when no store at all is readable (member without a store). */
    public boolean excludesEveryStore() {
        return restricted && storeId == null;
    }

    /** The single store the SQL must be limited to, or {@code null} for every store (unrestricted). */
    public UUID onlyStoreId() {
        return restricted ? storeId : null;
    }

    /**
     * The store a store-specific read must use: the requested one when readable, the member's own store when none was
     * requested ({@code null} for an owner who requested none).
     *
     * @throws DomainException FORBIDDEN (403, French message) when the store is outside the scope, or when a member
     *                         without a store reads a store
     */
    public UUID requireReadable(UUID requested) {
        UUID store = requested != null ? requested : onlyStoreId();
        if (restricted && (store == null || !allows(store))) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, OTHER_STORE_MESSAGE);
        }
        return store;
    }
}
