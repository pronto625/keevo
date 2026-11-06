package com.keevo.crm.contact.application.service;

import com.keevo.crm.contact.domain.service.ClientVisibility;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 20.2 (ADR-08) — resolves, through the store module's API (the crm never reads the {@code stores} table), what
 * an actor sees ({@link ClientVisibility.Scope}, at most one stores query) and the store of a new client.
 */
@Service
public class ClientStoreScope {

    /** Details key of the « unknown store » refusal: the REST adapter answers 400 for it. */
    public static final String STORE_FIELD = "storeId";

    private final StoreQueryPort storeQuery;
    private final ClientVisibleStores visibleStores;

    /** Without memo (tests, tools): every check reads the store module. */
    public ClientStoreScope(StoreQueryPort storeQuery) {
        this(storeQuery, storeQuery::clientVisibleStoreIds);
    }

    /** {@code visibleStores}: memoized per request (one stores query per request or sync batch). */
    @org.springframework.beans.factory.annotation.Autowired
    public ClientStoreScope(StoreQueryPort storeQuery, ClientVisibleStores visibleStores) {
        this.storeQuery = storeQuery;
        this.visibleStores = visibleStores;
    }

    /**
     * What {@code access} may see. A store member: its store (+ the sharing stores when its store shares) and the
     * legacy clients. An OWNER: everything, or what is visible from {@code ownerStoreFilter} when given.
     */
    public ClientVisibility.Scope readScope(ClientAccess access, UUID ownerStoreFilter, String tenantSchema) {
        if (access.owner()) {
            if (ownerStoreFilter == null) return ClientVisibility.Scope.UNRESTRICTED;
            Set<UUID> fromFilter = visibleStores.of(tenantSchema, ownerStoreFilter);
            // an active store always sees itself: empty = unknown or inactive store → 400 (field storeId)
            if (fromFilter.isEmpty()) throw unknownStore();
            return ClientVisibility.scope(true, ownerStoreFilter, fromFilter);
        }
        UUID own = access.assignedStoreId();
        Set<UUID> visible = own == null ? Set.of() : visibleStores.of(tenantSchema, own);
        return ClientVisibility.scope(false, null, visible);
    }

    /**
     * The store of a new client. A store member always gets its own store; another {@code requested} store →
     * {@code FORBIDDEN}. An OWNER gets {@code requested} (an active store of the tenant, else {@code VALIDATION_ERROR}
     * with details {@code field = storeId}) or, without it, the oldest active store ({@code null} when none).
     */
    public UUID storeForNewClient(ClientAccess access, UUID requested, String tenantSchema) {
        if (!access.owner()) {
            UUID own = access.assignedStoreId();
            if (own == null) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Store member without an assigned store");
            }
            if (requested != null && !requested.equals(own)) {
                throw new DomainException(ErrorCode.FORBIDDEN, "A store member cannot create a client in another store");
            }
            // a deactivated (or unknown) store sees nothing: its member could not see the client it creates
            if (!visibleStores.of(tenantSchema, own).contains(own)) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Store member of an inactive store");
            }
            return own;
        }
        if (requested != null) {
            return requireActiveStore(requested, tenantSchema);
        }
        return storeQuery.findAllActive(tenantSchema).stream()
                .filter(s -> s.id() != null)
                .min(Comparator.comparing(StoreView::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StoreView::id))
                .map(StoreView::id)
                .orElse(null);
    }

    /** The given store when it is an active store of the tenant, else {@code VALIDATION_ERROR} (field storeId). */
    public UUID requireActiveStore(UUID storeId, String tenantSchema) {
        return storeQuery.findById(tenantSchema, storeId)
                .filter(StoreView::active)
                .map(StoreView::id)
                .orElseThrow(ClientStoreScope::unknownStore);
    }

    private static DomainException unknownStore() {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                "Établissement inconnu ou désactivé", Map.of("field", STORE_FIELD));
    }

    /** Every active store of the tenant (an OWNER's « all my stores » duplicate check). One query. */
    public java.util.List<UUID> activeStoreIds(String tenantSchema) {
        return storeQuery.findAllActive(tenantSchema).stream().map(StoreView::id).toList();
    }

    /** The store's name for audit details (never its id); {@code null} for none / unknown. */
    public String storeName(String tenantSchema, UUID storeId) {
        if (storeId == null) return null;
        return storeQuery.findById(tenantSchema, storeId).map(StoreView::name).orElse(null);
    }

    /** Whether the refusal is the « unknown store » one of {@link #requireActiveStore}. */
    public static boolean isUnknownStore(DomainException e) {
        return e.getErrorCode() == ErrorCode.VALIDATION_ERROR && e.getDetails() != null
                && STORE_FIELD.equals(e.getDetails().get("field"));
    }
}
