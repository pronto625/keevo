package com.keevo.expenses.expense.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.10 (ADR-08, same rules as the 20.2 clients) — the store an actor's expenses live in, read through the
 * store module's API (the module never reads the {@code stores} table). At most one store query per call. Every
 * refusal carries a French message telling the user what to do.
 */
@Service
public class ExpenseStoreScope {

    /** Details key of the « unknown store » refusal: the REST list answers 400 for it. */
    public static final String STORE_FIELD = "storeId";

    static final String NO_STORE =
            "Votre compte n'est rattaché à aucune boutique : demandez au propriétaire de vous en attribuer une.";
    static final String INACTIVE_STORE =
            "Votre boutique est désactivée : demandez au propriétaire de la réactiver pour gérer ses dépenses.";
    static final String OTHER_STORE = "Vous ne pouvez gérer que les dépenses de votre boutique.";
    static final String MOVE_REFUSED = "Seul le propriétaire peut changer la boutique d'une dépense.";

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
     * {@code VALIDATION_ERROR} field {@code storeId}) or, without it, the tenant's default store
     * ({@link StoreQueryPort#defaultStore}: a shop before a warehouse, then the oldest; none →
     * {@code VALIDATION_ERROR}).
     */
    public UUID storeForNew(ExpenseAccess access, UUID requested, String tenantSchema) {
        if (!access.owner()) {
            return memberStore(access, requested, tenantSchema);
        }
        if (requested != null) {
            return activeStore(requested, tenantSchema).orElseThrow(ExpenseStoreScope::unknownStore);
        }
        // Story 20.14 — the one default-store policy of every module (a shop before a warehouse, then the oldest)
        return stores.defaultStore(tenantSchema)
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                        "Aucun établissement actif : créez une boutique avant d'enregistrer une dépense",
                        Map.of("field", STORE_FIELD)));
    }

    /**
     * The store of an edited expense: {@code requested} null = unchanged. A store member follows the creation rule
     * (their own active store; a move → {@code FORBIDDEN}); an OWNER may move it to another active store.
     */
    public UUID storeForEdit(ExpenseAccess access, UUID current, UUID requested, String tenantSchema) {
        if (!access.owner()) {
            if (requested != null && !requested.equals(current)) {
                throw forbidden(MOVE_REFUSED);
            }
            return memberStore(access, current, tenantSchema);
        }
        if (requested == null || requested.equals(current)) {
            return current;
        }
        return activeStore(requested, tenantSchema).orElseThrow(ExpenseStoreScope::unknownStore);
    }

    /** The display names of the stores of a page (one query). */
    public Map<UUID, String> names(String tenantSchema, Collection<UUID> storeIds) {
        return storeIds.isEmpty() ? Map.of() : stores.namesByIds(tenantSchema, storeIds);
    }

    private UUID memberStore(ExpenseAccess access, UUID requested, String tenantSchema) {
        UUID own = access.assignedStoreId();
        if (own == null) {
            throw forbidden(NO_STORE);
        }
        if (requested != null && !requested.equals(own)) {
            throw forbidden(OTHER_STORE);
        }
        if (activeStore(own, tenantSchema).isEmpty()) {
            throw forbidden(INACTIVE_STORE);
        }
        return own;
    }

    private Optional<UUID> activeStore(UUID storeId, String tenantSchema) {
        return stores.findById(tenantSchema, storeId).filter(StoreView::active).map(StoreView::id);
    }

    private static DomainException forbidden(String message) {
        return DomainException.userFacing(ErrorCode.FORBIDDEN, message);
    }

    private static DomainException unknownStore() {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, StoreQueryPort.UNKNOWN_OR_INACTIVE_STORE,
                Map.of("field", STORE_FIELD));
    }

    /** Whether the refusal is the « unknown store » one. */
    public static boolean isUnknownStore(DomainException e) {
        return e.getErrorCode() == ErrorCode.VALIDATION_ERROR && e.getDetails() != null
                && STORE_FIELD.equals(e.getDetails().get("field"));
    }
}
