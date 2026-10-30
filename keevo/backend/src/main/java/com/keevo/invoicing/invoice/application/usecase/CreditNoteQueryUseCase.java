package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteDocumentRenderer;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads credit notes (Story 14.7) and renders their PDF on demand (nothing stored: a credit note is immutable; only
 * the logo is the current one). The seller identity printed is the snapshot of the credited invoice.
 */
@Service
public class CreditNoteQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreditNoteQueryUseCase.class);

    private final CreditNoteRepository creditNotes;
    private final InvoiceRepository invoices;
    private final CreditNoteDocumentRenderer renderer;
    private final CompanyIdentity companyIdentity;

    public CreditNoteQueryUseCase(CreditNoteRepository creditNotes, InvoiceRepository invoices,
                                  CreditNoteDocumentRenderer renderer, CompanyIdentity companyIdentity) {
        this.creditNotes = creditNotes;
        this.invoices = invoices;
        this.renderer = renderer;
        this.companyIdentity = companyIdentity;
    }

    /** @throws DomainException {@code CREDIT_NOTE_NOT_FOUND} */
    @Transactional(readOnly = true)
    public CreditNote get(UUID id) {
        return creditNotes.findById(id).orElseThrow(CreditNoteQueryUseCase::notFound);
    }

    /** The credit note as an A4 PDF ({@code AV-2026-000001.pdf}). @throws DomainException CREDIT_NOTE_NOT_FOUND */
    @Transactional(readOnly = true)
    public InvoiceDocumentUseCase.InvoiceDocument pdf(UUID id, String tenantSchema) {
        CreditNote note = get(id);
        Invoice invoice = invoices.findById(note.invoiceId()).orElseThrow(CreditNoteQueryUseCase::notFound);
        return new InvoiceDocumentUseCase.InvoiceDocument(renderer.render(note, invoice, currentLogo(tenantSchema)),
                note.number().concat(".pdf"));
    }

    private Logo currentLogo(String tenantSchema) {
        try {
            return companyIdentity.logo(tenantSchema).orElse(null);
        } catch (RuntimeException e) {
            log.warn("CREDIT_NOTE_PDF_LOGO_UNREADABLE error={}", e.getClass().getSimpleName());
            return null;
        }
    }

    static DomainException notFound() {
        return new DomainException(ErrorCode.CREDIT_NOTE_NOT_FOUND, "Credit note not found");
    }
}
