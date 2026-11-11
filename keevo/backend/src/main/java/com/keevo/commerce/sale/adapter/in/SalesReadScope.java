package com.keevo.commerce.sale.adapter.in;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.ActorPermissions;
import com.keevo.shared.infrastructure.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Story 19.5b — which sales history an actor reads (REST {@code GET /api/v1/sales/history} and the AI tool
 * {@code sales_history}), resolved once by the inbound adapter and handed to the use case as plain values:
 * <ul>
 *   <li>OWNER: the requested store, the optional employee filter;</li>
 *   <li>store member holding {@value #PERMISSION} (MANAGER, VIEWER by default): every sale of HIS store, the optional
 *       employee filter honoured;</li>
 *   <li>any other store member (EMPLOYEE): his own sales of his store;</li>
 *   <li>a store member asking another store → 403 « Vous ne pouvez consulter que les ventes de votre boutique. »; a
 *       store member without store → 403 « Votre compte n'est rattaché à aucune boutique… »; any other actor → 403
 *       (fails closed).</li>
 * </ul>
 * <b>Hook for story 19-14:</b> the permission becomes the role checkbox « Voir les ventes de toute la boutique »; this
 * class does not change. Cost O(1): set lookups, no query.
 */
@Component
public class SalesReadScope {

    /** The permission granting every sale of the member's store (declared by the {@code pos} manifest). */
    public static final String PERMISSION = "STORE_SALES_VIEW";

    static final String OTHER_STORE = "Vous ne pouvez consulter que les ventes de votre boutique.";
    static final String NO_STORE =
            "Votre compte n'est rattaché à aucune boutique : demandez au propriétaire de vous en attribuer une.";

    private final ActorPermissions permissions;

    public SalesReadScope(ActorPermissions permissions) {
        this.permissions = permissions;
    }

    /**
     * The store and employee filter a history read must use.
     *
     * @param store           the requested store; {@code null} lets a store member read his own store (the owner must
     *                        give one — the caller decides how to refuse)
     * @param employeeFilter  the requested employee filter, {@code null} for none
     * @param ownSalesOnly    {@code true} when the actor only sees his own sales (the filter was forced to him)
     */
    public record Resolved(UUID store, UUID employeeFilter, boolean ownSalesOnly) {
    }

    /**
     * @throws DomainException FORBIDDEN (French message) for another store, or a store member without store
     */
    public Resolved resolve(UUID requestedStore, UUID requestedEmployee) {
        if (CurrentActor.isOwner()) {
            return new Resolved(requestedStore, requestedEmployee, false);
        }
        if (!CurrentActor.isStoreMember()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Sales history: neither owner nor store member");
        }
        UUID own = CurrentActor.assignedStoreIdOrNull();
        if (own == null) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, NO_STORE);
        }
        if (requestedStore != null && !requestedStore.equals(own)) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, OTHER_STORE);
        }
        if (permissions.currentActorHolds(PERMISSION)) {
            return new Resolved(own, requestedEmployee, false);
        }
        return new Resolved(own, CurrentActor.actorId(), true);
    }
}
