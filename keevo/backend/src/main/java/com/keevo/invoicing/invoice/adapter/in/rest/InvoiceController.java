package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.SendInvoiceRequestDto;
import com.keevo.invoicing.invoice.application.usecase.InvoiceClientResolver;
import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.CancelInvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceClientDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceClientSuggestionsDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceLineRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceResponseDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.PayInvoiceRequestDto;
import com.keevo.invoicing.invoice.application.usecase.InvoiceLifecycleUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoiceQueryUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicingSettingsUseCase;
import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase;
import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase.DraftContent;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Invoices of the tenant — Story 14.1 (« Facturation », standalone). Every handler is gated by the {@code invoicing}
 * module ({@code @RequiresEntitlement}: GET → READ, other verbs → WRITE) and a module permission
 * ({@code @RequiresPermission}, evaluated first): view → {@code INVOICE_VIEW}; create / edit / delete a draft →
 * {@code INVOICE_CREATE}; issue / record the payment → {@code INVOICE_SEND}; cancel → {@code INVOICE_MANAGE} (owner).
 * Story 14.3: CRM client suggestions ({@code GET /clients}, {@code INVOICE_CREATE}) and CRM link of the client.
 */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    static final int MAX_PAGE_SIZE = 100;

    /** Canonical UUID text (UUID.fromString alone accepts misplaced dashes). */
    private static final java.util.regex.Pattern CANONICAL_UUID =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final ManageDraftInvoiceUseCase drafts;
    private final InvoiceLifecycleUseCase lifecycle;
    private final InvoiceQueryUseCase queries;
    private final InvoicingSettingsUseCase settings;
    private final InvoiceDocumentUseCase documents;
    private final SendInvoiceUseCase sending;
    private final PublicBaseUrl publicBaseUrl;
    private final InvoiceClientResolver clients;

    public InvoiceController(ManageDraftInvoiceUseCase drafts, InvoiceLifecycleUseCase lifecycle,
                             InvoiceQueryUseCase queries, InvoicingSettingsUseCase settings,
                             InvoiceDocumentUseCase documents, SendInvoiceUseCase sending,
                             PublicBaseUrl publicBaseUrl, InvoiceClientResolver clients) {
        this.clients = clients;
        this.drafts = drafts;
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.settings = settings;
        this.documents = documents;
        this.sending = sending;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** POST /api/v1/invoices — a new draft. */
    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> create(@Valid @RequestBody InvoiceRequestDto request) {
        Invoice draft = drafts.create(content(request), CurrentActor.actorId(), TenantContext.getCurrentTenant());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(dto(draft)));
    }

    /**
     * GET /api/v1/invoices?status=&saleId=&page=&size= — newest first. Story 14.5: {@code saleId} = only the invoice
     * created from that sale by the POS bridge (0 or 1 item; empty while not created yet).
     */
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<InvoiceResponseDto>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String saleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new InvalidRequestParameterException("page", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestParameterException("size", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        InvoiceStatus filter = parseStatus(status);
        InvoiceRepository.Page result = queries.list(filter, parseSaleId(saleId), page, size);
        InvoicingSettings current = settings.current();
        var credits = queries.credits(result.items());
        List<InvoiceResponseDto> items = result.items().stream()
                .map(i -> dto(i, current, credits.getOrDefault(i.id(), InvoiceQueryUseCase.Credits.NONE)))
                .toList();
        int totalPages = (int) ((result.totalElements() + size - 1) / size);
        return ResponseEntity.ok(ApiResponseWrapper.paginated(items, page, size, result.totalElements(), totalPages));
    }

    /**
     * GET /api/v1/invoices/clients?search=&limit=20 — Story 14.3: the CRM clients for the invoice client picker
     * (non-archived, sorted by name, at most {@code limit}, clamped to 1–50). {@code crmAvailable=false} and no client
     * while {@code crm} is off for the tenant: the app then shows a free-text field.
     */
    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/clients")
    public ResponseEntity<ApiResponseWrapper<InvoiceClientSuggestionsDto>> clientSuggestions(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "" + InvoiceClientResolver.DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(InvoiceClientSuggestionsDto.from(
                clients.suggest(search, limit, TenantContext.getCurrentTenant()))));
    }

    /** GET /api/v1/invoices/{id}. */
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(queries.get(id))));
    }

    /** PUT /api/v1/invoices/{id} — replaces the content of a draft. */
    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> update(@PathVariable UUID id,
                                                                         @Valid @RequestBody InvoiceRequestDto request) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(drafts.update(id, content(request), TenantContext.getCurrentTenant()))));
    }

    /** DELETE /api/v1/invoices/{id} — a draft only (an issued invoice is never deleted). */
    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(InvoicingModule.ID)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        drafts.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/v1/invoices/{id}/issue — number, issue date and seller snapshot. */
    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/issue")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> issue(@PathVariable UUID id) {
        Invoice issued = lifecycle.issue(id, CurrentActor.actorId(), TenantContext.getCurrentTenant());
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(issued)));
    }

    /** POST /api/v1/invoices/{id}/pay — records the payment of an issued invoice. */
    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/pay")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> pay(
            @PathVariable UUID id, @Valid @RequestBody(required = false) PayInvoiceRequestDto request) {
        Invoice paid = lifecycle.pay(id, request == null ? null : request.paymentMethod(), CurrentActor.actorId(),
                TenantContext.getCurrentTenant());
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(paid)));
    }

    /**
     * POST /api/v1/invoices/{id}/cancel — an issued (unpaid) invoice without credit note; its number is kept. A paid
     * invoice, or one with a credit note, is credited instead (Story 14.7, {@code POST /{id}/credit-notes}).
     */
    @RequiresPermission(InvoicingModule.INVOICE_MANAGE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelInvoiceRequestDto request) {
        Invoice cancelled = lifecycle.cancel(id, request.reason(), CurrentActor.actorId(),
                TenantContext.getCurrentTenant());
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(cancelled)));
    }

    /** GET /api/v1/invoices/{id}/pdf — Story 14.2: the invoice as an A4 PDF, rendered on demand. */
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        return pdfResponse(documents.pdf(id, TenantContext.getCurrentTenant()));
    }

    /**
     * POST /api/v1/invoices/{id}/send {@code {phone?}} — Story 14.2: WhatsApp message with a signed link to the PDF
     * (ISSUED / PAID only; the client's phone by default).
     */
    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/send")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> send(
            @PathVariable UUID id, @Valid @RequestBody(required = false) SendInvoiceRequestDto request,
            HttpServletRequest http) {
        Invoice sent = sending.send(id, request == null ? null : request.phone(), publicBaseUrl.resolve(http),
                CurrentActor.actorId(), TenantContext.getCurrentTenant());
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(sent)));
    }

    /** {@code application/pdf}, displayed inline, never cached by a shared cache. */
    static ResponseEntity<byte[]> pdfResponse(InvoiceDocumentUseCase.InvoiceDocument document) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(document.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(document.content());
    }

    private InvoiceResponseDto dto(Invoice invoice) {
        boolean draft = invoice.status() == InvoiceStatus.DRAFT;
        return dto(invoice, draft ? settings.current() : null, draft ? InvoiceQueryUseCase.Credits.NONE
                : queries.credits(List.of(invoice)).getOrDefault(invoice.id(), InvoiceQueryUseCase.Credits.NONE));
    }

    private static InvoiceResponseDto dto(Invoice invoice, InvoicingSettings current,
                                          InvoiceQueryUseCase.Credits credits) {
        return InvoiceResponseDto.fromDomain(invoice, current, credits.notes(), credits.fullyCredited());
    }

    private static InvoiceStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return InvoiceStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException("status", "unknown invoice status");
        }
    }

    private static UUID parseSaleId(String saleId) {
        if (saleId == null || saleId.isBlank()) {
            return null;
        }
        String value = saleId.strip();
        if (!CANONICAL_UUID.matcher(value).matches()) {
            throw new InvalidRequestParameterException("saleId", "invalid sale id");
        }
        return UUID.fromString(value);
    }

    private static DraftContent content(InvoiceRequestDto request) {
        InvoiceClientDto c = request.client();
        ClientInfo client = new ClientInfo(c.id(), c.name(), c.niu(), c.address(), c.phone());
        List<InvoiceLineRequestDto> lines = request.lines() == null ? List.of() : request.lines();
        List<LineInput> inputs = lines.stream().map(l -> new LineInput(l.description(), l.quantity(), l.unitPrice(),
                l.vatTreatment() == null ? VatTreatment.STANDARD : VatTreatment.valueOf(l.vatTreatment()))).toList();
        return new DraftContent(client, inputs);
    }
}
