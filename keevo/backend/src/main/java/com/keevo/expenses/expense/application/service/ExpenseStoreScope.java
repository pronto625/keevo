package com.keevo.expenses.expense.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.10 (ADR-08, same rules as the 20.2 clients) — the store an actor's expenses live in, read through the
 * store module's API (the module never reads the {@code stores} table). At most one store query per call.
 */
@Service
public class ExpenseStoreScope {

    /** Details key of the « unknown store » refusal: the REST list answers 400 for it. */
    public static final String STORE_FIELD = "storeId";

    /** What a list may read: every store ({@link #ALL}), one store, or nothing ({@link #NONE}). */
    public record ListScope(boolean none, UUID storeId) {
        public static final ListScope ALL = new ListScope(false, null);
        public static final ListScope NONE = new ListScope(true, null);

        static ListScope of(UUID storeId) {
            return new ListScope(false, storeId);
        }
    }

    private final StoreQueryPort stores;

    public ExpenseStoreScope(StoreQueryPort stores) {
        this.stores = stores;
    }

    /**
     * An OWNER: every store, or {@code ownerFilter} when given (a store of the tenant, active or not — its past
     * expenses stay readable — else {@code VALIDATION_ERROR} field {@code storeId}). Anyone else: their store only
     * (the filter is ignored, as for clients); no store → nothing.
     */
    public ListScope listScope(ExpenseAccess access, UUID ownerFilter, String tenantSchema) {
        if (access.owner()) {
            if (ownerFilter == null) {
                return ListScope.ALL;
            }
            if (stores.findById(tenantSchema, ownerFilter).isEmpty()) {
                throw unknownStore();
            }
            return ListScope.of(ownerFilter);
        }
        return access.assignedStoreId() == null ? ListScope.NONE : ListScope.of(access.assignedStoreId());
    }

    /**
     * The store of a new expense. A store member: their own active store ({@code requested} another one, no store or
     * an inactive one → {@code FORBIDDEN}). An OWNER: {@code requested} (an active store, else
     * {@code VALIDATION_ERROR} field {@code storeId}) or, without it, the oldest active store (none →
     * {@code VALIDATION_ERROR}).
     */
    public UUID storeForNew(ExpenseAccess access, UUID requested, String tenantSchema) {
        if (!access.owner()) {
            UUID own = access.assignedStoreId();
            if (own == null) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Store member without an assigned store");
            }
            if (requested != null && !requested.equals(own)) {
                throw new DomainException(ErrorCode.FORBIDDEN, "A store member cannot record an expense of another store");
            }
            if (activeStore(own, tenantSchema).isEmpty()) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Store member of an inactive store");
            }
            return own;
        }
        if (requested != null) {
            return activeStore(requested, tenantSchema).orElseThrow(ExpenseStoreScope::unknownStore);
        }
        return stores.findAllActive(tenantSchema).stream()
                .filter(s -> s.id() != null)
                .min(Comparator.comparing(StoreView::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StoreView::id))
                .map(StoreView::id)
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                        "Aucun établissement actif : créez une boutique avant d'enregistrer une dépense",
                        Map.of("field", STORE_FIELD)));
    }

    /**
     * The store an edited expense moves to: {@code requested} null = unchanged; an OWNER may move it to another
     * active store; a member may not move it ({@code FORBIDDEN}).
     */
    public UUID storeForEdit(ExpenseAccess access, UUID current, UUID requested, String tenantSchema) {
        if (requested == null || requested.equals(current)) {
            return current;
        }
        if (!access.owner()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "A store member cannot move an expense to another store");
        }
        return activeStore(requested, tenantSchema).orElseThrow(ExpenseStoreScope::unknownStore);
    }

    private Optional<UUID> activeStore(UUID storeId, String tenantSchema) {
        return stores.findById(tenantSchema, storeId).filter(StoreView::active).map(StoreView::id);
    }

    private static DomainException unknownStore() {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, "Établissement inconnu ou désactivé",
                Map.of("field", STORE_FIELD));
    }

    /** Whether the refusal is the « unknown store » one. */
    public static boolean isUnknownStore(DomainException e) {
        return e.getErrorCode() == ErrorCode.VALIDATION_ERROR && e.getDetails() != null
                && STORE_FIELD.equals(e.getDetails().get("field"));
    }
}
