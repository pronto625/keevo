package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reads invoices of the current tenant (Story 14.1). */
@Service
public class InvoiceQueryUseCase {

    private final InvoiceRepository invoices;

    public InvoiceQueryUseCase(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public Invoice get(UUID id) {
        return invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
    }

    @Transactional(readOnly = true)
    public InvoiceRepository.Page list(InvoiceStatus status, UUID saleId, int page, int size) {
        return invoices.list(status, saleId, page, size);
    }

    static DomainException notFound() {
        return new DomainException(ErrorCode.INVOICE_NOT_FOUND, "Invoice not found");
    }
}
