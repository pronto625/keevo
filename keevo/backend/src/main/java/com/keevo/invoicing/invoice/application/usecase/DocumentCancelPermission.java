package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.ActorPermissions;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Story 19.5b — who may cancel a document (REST {@code POST /invoices/{id}/cancel} and sync {@code CANCEL_INVOICE}),
 * one policy for both:
 * <ul>
 *   <li>an INVOICE (numbered, fiscal): {@value InvoicingModule#INVOICE_MANAGE} (owner by default), unchanged;</li>
 *   <li>a QUOTE (never numbered, no fiscal effect): {@value #QUOTE_PERMISSION}, the right that already deletes it — an
 *       employee may end his own quote with a reason instead of deleting it.</li>
 * </ul>
 * Runs after the visibility check ({@link DocumentAccess}: out of scope → 404 first), reusing the owner and kind that
 * check read. Cost: no query for a holder of {@code INVOICE_MANAGE} nor for a restricted actor (kind already read);
 * one primary-key read of the kind otherwise (an unrestricted actor without {@code INVOICE_MANAGE}). An unknown id
 * passes through to the use case (its own not-found error). <b>Hook for story 19-14:</b> the two permission names stay; only the role mapping changes.
 */
@Service
public class DocumentCancelPermission {

    /** The permission ending a quote: the same as creating, editing and deleting it. */
    public static final String QUOTE_PERMISSION = InvoicingModule.INVOICE_CREATE;

    private final InvoiceRepository invoices;
    private final ActorPermissions permissions;

    public DocumentCancelPermission(InvoiceRepository invoices, ActorPermissions permissions) {
        this.invoices = invoices;
        this.permissions = permissions;
    }

    /**
     * @param known the document's owner and kind already read by {@link DocumentAccess#requireInvoice} ({@code null}:
     *              read it here, only for an actor without {@code INVOICE_MANAGE})
     * @throws DomainException FORBIDDEN when the current actor may not cancel this document
     */
    public void require(UUID invoiceId, com.keevo.invoicing.invoice.domain.model.DocumentScope.Owner known) {
        if (permissions.currentActorHolds(InvoicingModule.INVOICE_MANAGE)) {
            return;
        }
        java.util.Optional<Boolean> quote = known != null ? java.util.Optional.of(known.quote())
                : invoiceId == null ? java.util.Optional.empty() : invoices.isQuote(invoiceId);
        if (quote.isEmpty()) {
            return; // unknown document: the use case answers its own not-found error
        }
        if (!quote.get() || !permissions.currentActorHolds(QUOTE_PERMISSION)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Invoice cancellation not allowed for this role");
        }
    }
}
