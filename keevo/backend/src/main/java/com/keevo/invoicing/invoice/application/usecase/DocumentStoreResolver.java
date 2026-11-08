package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.store.api.StoreQueryPort;
import com.keevo.store.api.StoreView;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 20.14 (FUS-FR24) — the establishment of a new document (invoice or quote), decided once at its creation
 * (REST {@code POST /invoices}, sync {@code CREATE_INVOICE}, quote conversion); every later step keeps it.
 *
 * <p>Pattern: creation policy by actor kind, read through the store module's API (invoicing never reads the
 * {@code stores} table):
 * <ul>
 *   <li>a store member (EMPLOYEE, MANAGER, VIEWER) → its assigned store: another store requested → 403; its store
 *       deactivated → 403 (same rule as the expenses of 20.10); a member token without store → no store, and any
 *       {@code storeId} sent → 403 with its own message;</li>
 *   <li>an OWNER (or any unscoped actor) → the requested store when it is an active store of the tenant (else
 *       {@code VALIDATION_ERROR} field {@code storeId}, {@link StoreQueryPort#UNKNOWN_OR_INACTIVE_STORE}), else the
 *       tenant's default store ({@link StoreQueryPort#defaultStore}), {@code null} when it has no active store;</li>
 *   <li>a conversion → the quote's store while it is active, else the default store.</li>
 * </ul>
 * Cost: one store read ({@code findById} or {@code defaultStore}, {@code LIMIT 1}) per creation.
 */
@Service
public class DocumentStoreResolver {

    /** Details key of the « unknown store » refusal. */
    public static final String STORE_FIELD = "storeId";

    static final String OTHER_STORE = "Vous ne pouvez créer un document que dans votre boutique";
    static final String OWN_STORE_INACTIVE =
            "Votre boutique est désactivée : demandez au propriétaire de la réactiver pour créer des documents.";
    static final String NO_ASSIGNED_STORE = "Votre compte n'est rattaché à aucune boutique : créez le document sans "
            + "choisir de boutique, ou demandez au propriétaire de vous rattacher à une boutique.";

    private final StoreQueryPort stores;

    public DocumentStoreResolver(StoreQueryPort stores) {
        this.stores = stores;
    }

    /** The store of a new document of the current actor; {@code requested} = the optional {@code storeId} sent. */
    public UUID forNewDocument(UUID requested, String tenantSchema) {
        if (CurrentActor.isStoreMember() && !CurrentActor.isOwner()) {
            return forMember(CurrentActor.assignedStoreIdOrNull(), requested, tenantSchema);
        }
        return forOwner(requested, tenantSchema);
    }

    /** The store of the invoice converted from a quote of {@code quoteStore}: kept while active, else the default. */
    public UUID forConversion(UUID quoteStore, String tenantSchema) {
        if (quoteStore == null) {
            return null; // a quote made before V16: its invoice has no store either
        }
        return active(quoteStore, tenantSchema) ? quoteStore : stores.defaultStore(tenantSchema).orElse(null);
    }

    /**
     * The names of the stores of {@code documents} (a response or a list / delta page): one query for the page, none
     * when no document has a store.
     */
    public Map<UUID, String> namesOf(Collection<Invoice> documents, String tenantSchema) {
        Set<UUID> ids = documents.stream().map(Invoice::storeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : stores.namesByIds(tenantSchema, ids);
    }

    /** Whether {@code e} is the « unknown or inactive store » refusal. */
    public static boolean isUnknownStore(DomainException e) {
        return e.getErrorCode() == ErrorCode.VALIDATION_ERROR && e.getDetails() != null
                && STORE_FIELD.equals(e.getDetails().get("field"));
    }

    /** A store member: its own active store; another store, an inactive store or a store without member → 403. */
    UUID forMember(UUID own, UUID requested, String tenantSchema) {
        if (own == null) {
            if (requested != null) {
                throw DomainException.userFacing(ErrorCode.FORBIDDEN, NO_ASSIGNED_STORE);
            }
            return null;
        }
        if (requested != null && !requested.equals(own)) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, OTHER_STORE);
        }
        if (!active(own, tenantSchema)) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, OWN_STORE_INACTIVE);
        }
        return own;
    }

    /** An owner: the requested active store, else the default store, else none. */
    UUID forOwner(UUID requested, String tenantSchema) {
        if (requested != null) {
            if (!active(requested, tenantSchema)) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_ERROR, StoreQueryPort.UNKNOWN_OR_INACTIVE_STORE,
                        Map.of("field", STORE_FIELD, "reason", StoreQueryPort.UNKNOWN_OR_INACTIVE_STORE));
            }
            return requested;
        }
        return stores.defaultStore(tenantSchema).orElse(null);
    }

    private boolean active(UUID store, String tenantSchema) {
        return stores.findById(tenantSchema, store).map(StoreView::active).orElse(false);
    }
}
