package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.CreditNoteRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.CreditNoteResponseDto;
import com.keevo.invoicing.invoice.application.usecase.CreditNoteQueryUseCase;
import com.keevo.invoicing.invoice.application.usecase.IssueCreditNoteUseCase;
import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.CreditNoteSelection;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Credit notes — « avoirs » (Story 14.7). Gated like every invoicing handler: {@code @RequiresEntitlement("invoicing")}
 * (GET → READ, POST → WRITE) and a module permission evaluated first — issuing a credit note is an owner decision
 * ({@code INVOICE_MANAGE}, like cancelling an invoice); reading one or its PDF → {@code INVOICE_VIEW}.
 */
@RestController
@RequestMapping("/api/v1")
public class CreditNoteController {

    private final IssueCreditNoteUseCase issue;
    private final CreditNoteQueryUseCase queries;

    public CreditNoteController(IssueCreditNoteUseCase issue, CreditNoteQueryUseCase queries) {
        this.issue = issue;
        this.queries = queries;
    }

    /**
     * POST /api/v1/invoices/{id}/credit-notes {@code {reason, lines?: [{position, quantity}]}} — a credit note on an
     * issued or paid invoice; no {@code lines} = everything still to credit. 201 with the credit note.
     */
    @RequiresPermission(InvoicingModule.INVOICE_MANAGE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/invoices/{id}/credit-notes")
    public ResponseEntity<ApiResponseWrapper<CreditNoteResponseDto>> create(
            @PathVariable UUID id, @Valid @RequestBody CreditNoteRequestDto request) {
        List<CreditNoteSelection> selection = request.lines() == null ? null : request.lines().stream()
                .map(l -> new CreditNoteSelection(l.position(), l.quantity())).toList();
        CreditNote note = issue.issue(id, request.reason(), selection, CurrentActor.actorId(),
                TenantContext.getCurrentTenant());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(
                CreditNoteResponseDto.fromDomain(note)));
    }

    /** GET /api/v1/credit-notes/{id}. */
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/credit-notes/{id}")
    public ResponseEntity<ApiResponseWrapper<CreditNoteResponseDto>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(CreditNoteResponseDto.fromDomain(queries.get(id))));
    }

    /** GET /api/v1/credit-notes/{id}/pdf — the credit note as an A4 PDF, rendered on demand. */
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/credit-notes/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return InvoiceController.pdfResponse(queries.pdf(id, TenantContext.getCurrentTenant()));
    }
}
