package com.keevo.invoicing.invoice.domain.model;

import java.util.List;
import java.util.UUID;

/**
 * Story 20.14 — which documents (invoices, quotes, and credit notes through their invoice) an actor sees. User
 * decision of 2026-11-08, as Bref ({@code invoice.controller.ts} {@code assertCanAccess} / {@code assertListScope}):
 * <ul>
 *   <li>{@link #ALL} — the OWNER (and system work: the POS bridge, jobs);</li>
 *   <li>{@link #creator} — an EMPLOYEE (Bref « seller »): the documents they created ({@code created_by});</li>
 *   <li>{@link #store} — a MANAGER (Bref « admin ») or a VIEWER: the documents of their assigned store;</li>
 *   <li>{@link #NONE} — nothing (a store member without store, read by the sync pull).</li>
 * </ul>
 * Pure value: the predicate is {@link #sees} in memory and {@link #sql} in a query (one column equality, served by
 * the V16 indexes {@code idx_invoices_created_by_sync} / {@code idx_invoices_store_sync}).
 */
public record DocumentScope(boolean none, UUID creator, UUID store) {

    public static final DocumentScope ALL = new DocumentScope(false, null, null);
    public static final DocumentScope NONE = new DocumentScope(true, null, null);

    /** The creator and the store of one document (a credit note: those of its invoice). */
    public record Owner(UUID createdBy, UUID storeId, boolean quote) {

        /** Story 19.5b — the kind is read in the same primary-key query ({@code quote}: a QUOTE). */
        public Owner(UUID createdBy, UUID storeId) {
            this(createdBy, storeId, false);
        }
    }

    /** A condition of a query and its arguments ({@code TRUE} for {@link #ALL}). */
    public record Sql(String condition, List<Object> args) {
    }

    public static DocumentScope creator(UUID actor) {
        return new DocumentScope(false, java.util.Objects.requireNonNull(actor, "actor"), null);
    }

    public static DocumentScope store(UUID store) {
        return new DocumentScope(false, null, java.util.Objects.requireNonNull(store, "store"));
    }

    public boolean all() {
        return !none && creator == null && store == null;
    }

    /** Whether a document of {@code owner} is in this scope. */
    public boolean sees(Owner owner) {
        if (all()) {
            return true;
        }
        if (none || owner == null) {
            return false;
        }
        return creator != null ? creator.equals(owner.createdBy()) : store.equals(owner.storeId());
    }

    /** The predicate on the {@code invoices} columns, {@code alias} = the table alias ({@code null} = none). */
    public Sql sql(String alias) {
        String p = alias == null ? "" : alias + ".";
        if (all()) {
            return new Sql("TRUE", List.of());
        }
        if (none) {
            return new Sql("FALSE", List.of());
        }
        return creator != null ? new Sql(p + "created_by = ?", List.of(creator))
                : new Sql(p + "store_id = ?", List.of(store));
    }
}
