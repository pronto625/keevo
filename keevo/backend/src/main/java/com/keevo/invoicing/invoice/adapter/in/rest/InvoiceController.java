package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.SendInvoiceRequestDto;
import com.keevo.invoicing.invoice.application.usecase.InvoiceClientResolver;
import com.keevo.invoicing.invoice.application.usecase.InvoiceDocumentUseCase;
import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.AddInvoicePaymentRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.CancelInvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.SettleInvoicePaymentRequestDto;
import com.keevo.invoicing.invoice.application.usecase.InvoicePaymentUseCase;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.ConvertQuoteRequestDto;
import com.keevo.invoicing.invoice.application.usecase.ConvertQuoteUseCase;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceClientSuggestionsDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceResponseDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.PayInvoiceRequestDto;
import com.keevo.invoicing.invoice.application.usecase.InvoiceLifecycleUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoiceQueryUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicingSettingsUseCase;
import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase;
import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase.DraftContent;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Invoices of the tenant — Story 14.1 (« Facturation », standalone). Every handler is gated by the {@code invoicing}
 * module ({@code @RequiresEntitlement}: GET → READ, other verbs → WRITE) and a module permission
 * ({@code @RequiresPermission}, evaluated first): view → {@code INVOICE_VIEW}; create / edit / delete a draft →
 * {@code INVOICE_CREATE}; issue / record the payment → {@code INVOICE_SEND}; cancel → {@code INVOICE_MANAGE} (owner).
 * Story 14.3: CRM client suggestions ({@code GET /clients}, {@code INVOICE_CREATE}) and CRM link of the client.
 * Story 20.3: quotes ({@code kind QUOTE}) share these endpoints; {@code POST /{id}/convert} ({@code INVOICE_SEND}).
 * Story 20.4: collections — {@code POST /{id}/payments}, {@code POST /{id}/payments/{paymentId}/settle}
 * ({@code INVOICE_SEND}), list filter {@code paymentState}.
 */
@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    static final int MAX_PAGE_SIZE = 100;

    /** Story 15.5 — longest {@code search} text of the list. */
    static final int MAX_SEARCH_LENGTH = 100;

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
    private final ConvertQuoteUseCase conversions;
    private final InvoicePaymentUseCase payments;

    public InvoiceController(ManageDraftInvoiceUseCase drafts, InvoiceLifecycleUseCase lifecycle,
                             InvoiceQueryUseCase queries, InvoicingSettingsUseCase settings,
                             InvoiceDocumentUseCase documents, SendInvoiceUseCase sending,
                             PublicBaseUrl publicBaseUrl, InvoiceClientResolver clients,
                             ConvertQuoteUseCase conversions, InvoicePaymentUseCase payments) {
        this.payments = payments;
        this.clients = clients;
        this.conversions = conversions;
        this.drafts = drafts;
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.settings = settings;
        this.documents = documents;
        this.sending = sending;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * POST /api/v1/invoices — a new draft. Story 15.5b: {@code id} (optional) = the id chosen by the device; sent again,
     * the invoice already created with it is returned (201 as well), never a second one.
     */
    @RequiresPermission(InvoicingModule.INVOICE_CREATE)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> create(@Valid @RequestBody InvoiceRequestDto request) {
        Invoice draft = drafts.create(request.id(), content(request), CurrentActor.actorId(),
                TenantContext.getCurrentTenant());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(dto(draft)));
    }

    /**
     * GET /api/v1/invoices?status=&saleId=&search=&from=&to=&page=&size= — newest first. Story 14.5: {@code saleId} =
     * only the invoice created from that sale by the POS bridge (0 or 1 item; empty while not created yet). Story 15.5:
     * {@code search} = part of the number or of the client name (case-insensitive, trimmed, at most
     * {@value #MAX_SEARCH_LENGTH} characters); {@code from} / {@code to} = issue date range ({@code yyyy-MM-dd},
     * Africa/Douala calendar day, inclusive, {@code from <= to}) — drafts, not issued yet, are left out by a range.
     * Story 20.3: {@code kind} = {@code INVOICE} (absent: the V1 list, invoices only), {@code QUOTE} (quotes only) or
     * {@code ALL} (both); anything else → 400. Story 20.4: {@code paymentState} = {@code UNPAID} | {@code PARTIAL} |
     * {@code PAID} | {@code OVERDUE} (issued or paid invoices in that collection state); anything else → 400.
     */
    @RequiresPermission(InvoicingModule.INVOICE_VIEW)
    @RequiresEntitlement(value = InvoicingModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<InvoiceResponseDto>>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentState,
            @RequestParam(required = false) String saleId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new InvalidRequestParameterException("page", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestParameterException("size", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String text = search == null || search.isBlank() ? null : search.strip();
        if (text != null && text.indexOf('\0') >= 0) {
            throw new InvalidRequestParameterException("search", "NUL character in search",
                    "La recherche contient un caractère non autorisé");
        }
        if (text != null && text.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidRequestParameterException("search", "search too long",
                    "La recherche ne doit pas dépasser " + MAX_SEARCH_LENGTH + " caractères");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from must not be after to",
                    "La date de début doit précéder la date de fin");
        }
        InvoiceRepository.Page result = queries.list(
                new InvoiceRepository.Filter(parseStatus(status), parseSaleId(saleId), text, from, to,
                        parseKind(kind), parsePaymentState(paymentState)), page, size);
        InvoicingSettings current = settings.current();
        var credits = queries.credits(result.items());
        var collections = queries.collections(result.items(), credits); // one batched query for the page
        List<InvoiceResponseDto> items = result.items().stream()
                .map(i -> dto(i, current, credits.getOrDefault(i.id(), InvoiceQueryUseCase.Credits.NONE),
                        collections.get(i.id())))
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

    /**
     * PUT /api/v1/invoices/{id} — replaces the content of a draft (invoice or quote). Story 20.3:
     * {@code expectedUpdatedAt} (optional) = the {@code updatedAt} last read; changed since → 409 {@code OPTIMISTIC_LOCK}.
     */
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

    /**
     * POST /api/v1/invoices/{id}/pay {@code {paymentMethod?, paidAt?}} — records the payment of an issued invoice.
     * Story 15.5: {@code paidAt} = the day the client paid (today by default; never in the future nor before the issue
     * date → 400 {@code INVOICE_PAYMENT_DATE_INVALID}).
     */
    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/pay")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> pay(
            @PathVariable UUID id, @Valid @RequestBody(required = false) PayInvoiceRequestDto request) {
        Invoice paid = lifecycle.pay(id, request == null ? null : request.paymentMethod(),
                request == null ? null : request.paidAt(), CurrentActor.actorId(), TenantContext.getCurrentTenant());
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

    /**
     * POST /api/v1/invoices/{id}/payments {@code {paymentId?, amount, paidAt? | dueDate?, method?, note?}} — Story 20.4:
     * a payment received ({@code paidAt}, ISSUED invoice only; a deposit is the first one) or a scheduled instalment
     * ({@code dueDate}, DRAFT or ISSUED) — exactly one of the two. 201 with the invoice; the same {@code paymentId}
     * again → 200, nothing added (different content → 422 {@code VALIDATION_ERROR}). Above what remains due → 409
     * {@code OVERPAYMENT}. Fully paid → status PAID.
     */
    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/payments")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> addPayment(
            @PathVariable UUID id, @Valid @RequestBody AddInvoicePaymentRequestDto request) {
        InvoicePaymentUseCase.Result result = payments.add(id, request.toCommand(), CurrentActor.actorId(),
                TenantContext.getCurrentTenant());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponseWrapper.ok(dto(result.invoice())));
    }

    /**
     * POST /api/v1/invoices/{id}/payments/{paymentId}/settle {@code {paidAt?, method?}} — Story 20.4: the scheduled
     * instalment is paid ({@code paidAt} = the day, today by default; same date rules as {@code /pay}); already paid →
     * 200 unchanged.
     */
    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/payments/{paymentId}/settle")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> settlePayment(
            @PathVariable UUID id, @PathVariable UUID paymentId,
            @Valid @RequestBody(required = false) SettleInvoicePaymentRequestDto request) {
        InvoicePaymentUseCase.Result result = payments.settle(id, paymentId,
                request == null ? null : request.paidAt(), request == null ? null : request.method(),
                CurrentActor.actorId(), TenantContext.getCurrentTenant());
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto(result.invoice())));
    }

    /**
     * POST /api/v1/invoices/{quoteId}/convert {@code {invoiceId?, expectedUpdatedAt?}} — Story 20.3: the DRAFT quote becomes a new issued
     * invoice (DOCUMENTS quota, next {@code FAC-} number, issue date today); 201 with the invoice. Already converted →
     * 200 with the same invoice (idempotent). Same rights as the issue.
     */
    @RequiresPermission(InvoicingModule.INVOICE_SEND)
    @RequiresEntitlement(InvoicingModule.ID)
    @PostMapping("/{id}/convert")
    public ResponseEntity<ApiResponseWrapper<InvoiceResponseDto>> convert(
            @PathVariable UUID id, @RequestBody(required = false) ConvertQuoteRequestDto request) {
        ConvertQuoteUseCase.Conversion conversion = conversions.convert(id,
                request == null ? null : request.invoiceId(), request == null ? null : request.expectedUpdatedAt(),
                CurrentActor.actorId(), TenantContext.getCurrentTenant());
        return ResponseEntity.status(conversion.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponseWrapper.ok(dto(conversion.invoice())));
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
        Map<UUID, InvoiceQueryUseCase.Credits> credits = draft ? Map.of() : queries.credits(List.of(invoice));
        return dto(invoice, draft ? settings.current() : null,
                credits.getOrDefault(invoice.id(), InvoiceQueryUseCase.Credits.NONE),
                queries.collections(List.of(invoice), credits).get(invoice.id()));
    }

    private static InvoiceResponseDto dto(Invoice invoice, InvoicingSettings current,
                                          InvoiceQueryUseCase.Credits credits,
                                          PaymentStateCalculator.Collection collection) {
        return InvoiceResponseDto.fromDomain(invoice, current, credits.notes(), credits.fullyCredited(), collection);
    }

    /** Story 20.4 — {@code null}: not filtered; unknown → 400. */
    static PaymentState parsePaymentState(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PaymentState.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException("paymentState", "unknown payment state",
                    "État de paiement inconnu : choisissez « Non payée », « Partiellement payée », « Payée » ou « En retard »");
        }
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

    /** Story 20.3 — absent → invoices only (V1 list); {@code ALL} → {@code null} (both kinds). */
    static InvoiceKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return InvoiceKind.INVOICE;
        }
        String value = kind.strip().toUpperCase(java.util.Locale.ROOT);
        if ("ALL".equals(value)) {
            return null;
        }
        try {
            return InvoiceKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestParameterException("kind", "unknown document kind",
                    "Le type de document doit être INVOICE, QUOTE ou ALL");
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

    /**
     * The draft content; a line the domain refuses (e.g. an unknown VAT treatment, a quantity out of bounds) → French
     * 400, never a 500 (Story 15.5b — same rule as the sync path).
     */
    static DraftContent content(InvoiceRequestDto request) {
        try {
            return request.toDraftContent();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidRequestParameterException("lines", "invalid invoice line",
                    "Une ligne de la facture est invalide (traitement de TVA, quantité ou montant)");
        }
    }
}
