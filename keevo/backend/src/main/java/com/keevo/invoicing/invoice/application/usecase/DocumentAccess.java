package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.DocumentScope;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Story 20.14 — visibility of documents per role, user decision of 2026-11-08 reproducing Bref
 * ({@code invoice.controller.ts} {@code assertCanAccess}, {@code assertListScope}, {@code findAll}). One policy for
 * REST, sync and the sync pull (same pattern as {@code ClientVisibility} / {@code ExpenseStoreScope}):
 * <ul>
 *   <li>OWNER (and system work without an authenticated actor: POS bridge, jobs) → every document;</li>
 *   <li>MANAGER or VIEWER → the documents of their assigned store (a VIEWER cannot write: its permissions);</li>
 *   <li>EMPLOYEE → the documents they created (invoices, quotes, credit notes through their invoice);</li>
 *   <li>a store member without store → 403 « Votre compte n'est rattaché à aucune boutique… » (Bref
 *       {@code noShopAssigned}); the sync pull reads nothing instead of failing.</li>
 * </ul>
 * A document outside the scope answers 404 {@code INVOICE_NOT_FOUND}, as clients (20.2) and expenses (20.10): its
 * existence is never disclosed (Bref answers 403). Cost: one primary-key read of the document's creator and store per
 * action, none for an owner; lists and the pull add one indexed equality to their query.
 */
@Service
public class DocumentAccess {

    static final String NO_STORE =
            "Votre compte n'est rattaché à aucune boutique : demandez au propriétaire de vous en attribuer une.";
    static final String ONLY_OWNER_STORE_FILTER = "Seul le propriétaire peut filtrer les documents par boutique.";

    private final InvoiceRepository invoices;

    public DocumentAccess(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    /** The scope of the current actor; a store member without store → 403. */
    public DocumentScope current() {
        DocumentScope scope = resolve();
        if (scope.none()) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, NO_STORE);
        }
        return scope;
    }

    /** The scope of the sync pull: as {@link #current()}, but a member without store reads nothing (no error). */
    public DocumentScope forPull() {
        return resolve();
    }

    /**
     * The scope of a list with the optional explicit {@code storeId} filter: only the OWNER may pass it (Bref
     * {@code onlyOwnerShopId}: anyone else → 403, even with their own store).
     */
    public DocumentScope forList(UUID storeFilter) {
        DocumentScope scope = current();
        if (storeFilter != null && !scope.all()) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, ONLY_OWNER_STORE_FILTER);
        }
        return scope;
    }

    /** The document {@code invoiceId} (invoice or quote) is in the actor's scope, else 404 {@code INVOICE_NOT_FOUND}. */
    public void requireInvoice(UUID invoiceId) {
        DocumentScope scope = current();
        if (scope.all() || invoiceId == null) {
            return;
        }
        if (!invoices.ownerOf(invoiceId).map(scope::sees).orElse(false)) {
            throw InvoiceQueryUseCase.notFound();
        }
    }

    /**
     * A creation with an id chosen by the device: when a document already has that id and the actor does not see it,
     * 404 (never returned as a replay, never disclosed). A free id or a visible document: nothing.
     */
    public void requireCreatable(UUID invoiceId) {
        DocumentScope scope = current();
        if (scope.all() || invoiceId == null) {
            return;
        }
        if (invoices.ownerOf(invoiceId).map(owner -> !scope.sees(owner)).orElse(false)) {
            throw InvoiceQueryUseCase.notFound();
        }
    }

    /** The credit note {@code creditNoteId} belongs to a document of the actor's scope, else 404. */
    public void requireCreditNote(UUID creditNoteId) {
        DocumentScope scope = current();
        if (scope.all() || creditNoteId == null) {
            return;
        }
        if (!invoices.ownerOfCreditNote(creditNoteId).map(scope::sees).orElse(false)) {
            throw new DomainException(ErrorCode.CREDIT_NOTE_NOT_FOUND, "Credit note not found");
        }
    }

    private static DocumentScope resolve() {
        if (CurrentActor.authentication() == null || CurrentActor.isOwner() || CurrentActor.isSuperAdmin()
                || !CurrentActor.isStoreMember()) {
            return DocumentScope.ALL;
        }
        UUID store = CurrentActor.assignedStoreIdOrNull();
        if (store == null) {
            return DocumentScope.NONE;
        }
        if (CurrentActor.isManager() || CurrentActor.isViewer()) {
            return DocumentScope.store(store);
        }
        return DocumentScope.creator(CurrentActor.actorId());
    }
}
