package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.14 (FUS-FR24) — the establishment of a new document (invoice or quote), decided once at its creation
 * (REST {@code POST /invoices}, sync {@code CREATE_INVOICE}); every later step keeps it.
 *
 * <p>Pattern: creation policy by actor kind, read through the store module's API (invoicing never reads the
 * {@code stores} table):
 * <ul>
 *   <li>a store member (EMPLOYEE, MANAGER, VIEWER) → its assigned store, whatever it asks (another store requested →
 *       403); a member token without store → no store (the document is still made, as before V16);</li>
 *   <li>an OWNER (or any unscoped actor) → the requested store when it is an active store of the tenant (else 400,
 *       field {@code storeId}), else the oldest active shop (a warehouse only when the tenant has no shop),
 *       {@code null} when the tenant has no active store.</li>
 * </ul>
 * Cost: a member, none; an owner, one store read ({@code findById}, or the active stores — a handful of rows).
 */
@Service
public class DocumentStoreResolver {

    /** Details key of the « unknown store » refusal: the REST adapter answers 400 for it. */
    public static final String STORE_FIELD = "storeId";

    static final String OTHER_STORE = "Vous ne pouvez créer un document que dans votre boutique";
    static final String UNKNOWN_STORE = "Boutique inconnue ou désactivée";

    private final StoreQueryPort stores;

    public DocumentStoreResolver(StoreQueryPort stores) {
        this.stores = stores;
    }

    /** The store of a new document of the current actor; {@code requested} = the optional {@code storeId} sent. */
    public UUID forNewDocument(UUID requested, String tenantSchema) {
        if (CurrentActor.isStoreMember() && !CurrentActor.isOwner()) {
            return forMember(CurrentActor.assignedStoreIdOrNull(), requested);
        }
        return forOwner(requested, tenantSchema);
    }

    /**
     * The names of the stores of {@code documents} (a response or a list / delta page): one query for the page, none
     * when no document has a store.
     */
    public Map<UUID, String> namesOf(java.util.Collection<com.keevo.invoicing.invoice.domain.model.Invoice> documents,
                                     String tenantSchema) {
        java.util.Set<UUID> ids = documents.stream().map(com.keevo.invoicing.invoice.domain.model.Invoice::storeId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return ids.isEmpty() ? Map.of() : stores.namesByIds(tenantSchema, ids);
    }

    /** Whether {@code e} is the « unknown or inactive store » refusal (the REST adapter answers 400). */
    public static boolean isUnknownStore(DomainException e) {
        return e.getErrorCode() == ErrorCode.VALIDATION_ERROR && e.getDetails() != null
                && STORE_FIELD.equals(e.getDetails().get("field"));
    }

    /** A store member: its own store; another store requested → 403. */
    static UUID forMember(UUID own, UUID requested) {
        if (requested != null && !requested.equals(own)) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, OTHER_STORE);
        }
        return own;
    }

    /** An owner: the requested active store, else the oldest active shop (then warehouse), else none. */
    UUID forOwner(UUID requested, String tenantSchema) {
        if (requested != null) {
            return stores.findById(tenantSchema, requested)
                    .filter(StoreView::active)
                    .map(StoreView::id)
                    .orElseThrow(() -> DomainException.userFacing(ErrorCode.VALIDATION_ERROR, UNKNOWN_STORE,
                            Map.of("field", STORE_FIELD)));
        }
        return stores.findAllActive(tenantSchema).stream()
                .filter(s -> s.id() != null)
                .min(Comparator.comparing(StoreView::isWarehouse)
                        .thenComparing(StoreView::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StoreView::id))
                .map(StoreView::id)
                .orElse(null);
    }
}
