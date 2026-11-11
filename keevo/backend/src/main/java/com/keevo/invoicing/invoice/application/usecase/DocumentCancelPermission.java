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
 * Runs after the visibility check ({@link DocumentAccess}: out of scope → 404 first). Cost: no query for a holder of
 * {@code INVOICE_MANAGE}; one primary-key read of the kind otherwise. An unknown id passes through to the use case
 * (its own not-found error). <b>Hook for story 19-14:</b> the two permission names stay; only the role mapping changes.
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

    /** @throws DomainException FORBIDDEN when the current actor may not cancel this document */
    public void require(UUID invoiceId) {
        if (permissions.currentActorHolds(InvoicingModule.INVOICE_MANAGE)) {
            return;
        }
        boolean quote = invoiceId != null && invoices.isQuote(invoiceId).orElse(false);
        if (!quote || !permissions.currentActorHolds(QUOTE_PERMISSION)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Invoice cancellation not allowed for this role");
        }
    }
}
