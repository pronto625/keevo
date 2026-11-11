package com.keevo.invoicing.invoice.adapter.in.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.AddInvoicePaymentRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.CancelInvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.SettleInvoicePaymentRequestDto;
import com.keevo.invoicing.invoice.application.service.InvoiceSendOutbox;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.SendInvoiceRequestDto;
import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicePaymentUseCase;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.ConvertQuoteRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.PayInvoiceRequestDto;
import com.keevo.invoicing.invoice.application.usecase.ConvertQuoteUseCase;
import com.keevo.invoicing.invoice.application.usecase.DocumentAccess;
import com.keevo.invoicing.invoice.application.usecase.DocumentCancelPermission;
import com.keevo.invoicing.invoice.application.usecase.InvoiceLifecycleUseCase;
import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.application.ModulePermissions;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 15.5b — the offline invoice operations of the device's sync queue, applied by the same use cases as the REST
 * endpoints (same rules, same numbering, same events):
 * <ul>
 *   <li>{@code CREATE_INVOICE} {@code {id, client, lines}} — a draft with the id generated on the device; idempotent on
 *       that id (sent again → the invoice already created, no second one);</li>
 *   <li>{@code UPDATE_INVOICE} {@code {invoiceId, client, lines}} — replaces the content of the draft;</li>
 *   <li>{@code ISSUE_INVOICE} {@code {invoiceId}} — the number ({@code FAC-…}) and the issue date are the server's,
 *       allocated now (never on the device);</li>
 *   <li>{@code PAY_INVOICE} {@code {invoiceId, paymentMethod?, paidAt?}} — {@code paidAt} = the day the client paid
 *       ({@code yyyy-MM-dd}, today when absent), same bounds as {@code POST /pay};</li>
 *   <li>{@code CANCEL_INVOICE} {@code {invoiceId, reason}} (an invoice: owner; a quote: who may delete it — 19.5b).</li>
 *   <li>Story 20.3 — {@code CONVERT_QUOTE} {@code {quoteId, invoiceId?, expectedUpdatedAt?}} (entity = the quote): the quote becomes a new issued invoice
 *       ({@code invoiceId} = the id chosen on the device); replayed, the same invoice ({@code serverEntityId} = the
 *       invoice id). {@code CREATE_INVOICE} takes {@code kind} ({@code QUOTE} = a quote); {@code UPDATE_INVOICE} takes
 *       {@code expectedUpdatedAt} (stale → {@code REJECTED OPTIMISTIC_LOCK}).</li>
 *   <li>Story 20.4 — {@code ADD_INVOICE_PAYMENT} {@code {invoiceId, paymentId, amount, paidAt? | dueDate?, method?,
 *       note?}}: a payment received or a scheduled instalment (exactly one date), {@code paymentId} chosen on the
 *       device; replayed with the same content → APPLIED, no second row; the same {@code paymentId} with another
 *       content → {@code REJECTED VALIDATION_ERROR}; above what remains due → {@code REJECTED OVERPAYMENT}.
 *       {@code SETTLE_INVOICE_PAYMENT} {@code {invoiceId, paymentId, paidAt?, method?}}: the instalment is paid
 *       (already paid → APPLIED, unchanged). {@code serverEntityId} = the invoice id. {@code PAY_INVOICE} now settles
 *       the whole invoice (instalments paid, balance added).</li>
 *   <li>Story 20.14 — {@code CREATE_INVOICE} takes an optional {@code storeId} (the store the document is made in,
 *       ignored by {@code UPDATE_INVOICE}): a store member's own store, absent = its store; another store →
 *       {@code REJECTED FORBIDDEN} « Vous ne pouvez créer un document que dans votre boutique », its store deactivated
 *       or no store on its account with a {@code storeId} → {@code REJECTED FORBIDDEN} with its French message; an
 *       owner's unknown or deactivated store → {@code REJECTED VALIDATION_ERROR} « Boutique inconnue ou
 *       désactivée »; absent = the owner's default store. Nothing is created on a rejection; the French message is in
 *       {@code conflictData.message}.</li>
 *   <li>Story 20.14 — visibility (user decision 2026-11-08, as Bref): an EMPLOYEE acts only on the documents they
 *       created, a MANAGER / VIEWER on those of their store; any other document → {@code REJECTED
 *       INVOICE_NOT_FOUND} (never disclosed); a member without store → {@code REJECTED FORBIDDEN}.</li>
 *   <li>Story 20.8 — {@code SEND_INVOICE} {@code {invoiceId, channel? | channels?, phone?, email?}} (entity = the
 *       document): validated (sendable, recipient of each channel, French refusals) and QUEUED — one
 *       {@code invoice_send_requests} row per channel keyed on the {@code operationId}, in this per-operation
 *       transaction — then APPLIED. Nothing is sent inside the push: the outbox sends after commit (worker) or at the
 *       next poll, with retries. A pushed again operation queues nothing (DUPLICATE from the operation log, and the
 *       unique key). The outcome reaches the app through the pull ({@code lastSendStatus}, {@code lastSentChannel}).</li>
 * </ul>
 * Payloads are validated with the REST request DTOs (same French messages, {@code VALIDATION_ERROR}); permissions are
 * those of the matching endpoint ({@code @RequiresPermission} is not applied to sync handlers, so it is checked here
 * with the same role mapping) — a missing one → {@code FORBIDDEN}. The module gate is the push's ({@link #moduleId()}:
 * {@code MODULE_INACTIVE} / {@code MODULE_READ_ONLY}, held on the device). The module's tables are provisioned (lazily)
 * first, as for a gated REST call. The operations of one invoice apply in queue order (the device's
 * {@code clientTimestamp} order; the invoice is resolved by its device id) — a re-sent operation is answered
 * {@code DUPLICATE} by the push's operation log.
 */
@Component
public class InvoiceSyncHandler extends AbstractSyncOperationHandler {

    public static final String CREATE = "CREATE_INVOICE";
    public static final String UPDATE = "UPDATE_INVOICE";
    public static final String ISSUE = "ISSUE_INVOICE";
    public static final String PAY = "PAY_INVOICE";
    public static final String CANCEL = "CANCEL_INVOICE";
    /** Story 20.3 — {@code {quoteId, invoiceId}}: the quote becomes a new issued invoice (idempotent). */
    public static final String CONVERT_QUOTE = "CONVERT_QUOTE";

    /** Story 20.4 — a payment received or an instalment scheduled ({@code paymentId} chosen on the device). */
    public static final String ADD_PAYMENT = "ADD_INVOICE_PAYMENT";
    /** Story 20.4 — a scheduled instalment paid. */
    public static final String SETTLE_PAYMENT = "SETTLE_INVOICE_PAYMENT";
    /** Story 20.8 — the document sent to its client (WhatsApp, SMS or email), once per operationId. */
    public static final String SEND = "SEND_INVOICE";

    /** The permission of each operation: the one of its REST endpoint. */
    private static final Map<String, String> PERMISSIONS = Map.of(
            CREATE, InvoicingModule.INVOICE_CREATE,
            UPDATE, InvoicingModule.INVOICE_CREATE,
            ISSUE, InvoicingModule.INVOICE_SEND,
            PAY, InvoicingModule.INVOICE_SEND,
            CANCEL, DocumentCancelPermission.QUOTE_PERMISSION, // 19.5b — the lowest; invoices need more (see apply)
            CONVERT_QUOTE, InvoicingModule.INVOICE_SEND,
            ADD_PAYMENT, InvoicingModule.INVOICE_SEND,
            SETTLE_PAYMENT, InvoicingModule.INVOICE_SEND,
            SEND, InvoicingModule.INVOICE_SEND);

    private final ManageDraftInvoiceUseCase drafts;
    private final InvoiceLifecycleUseCase lifecycle;
    private final ConvertQuoteUseCase conversions;
    private final InvoicePaymentUseCase payments;
    private final SendInvoiceUseCase sending;
    private final InvoiceSendOutbox outbox;
    private final ModuleProvisioner provisioner;
    private final ModulePermissions permissions;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    /** Story 20.14 — the documents the actor sees (same policy as REST). */
    private final DocumentAccess access;

    /** Story 19.5b — who may cancel which document (same policy as REST). */
    private final DocumentCancelPermission cancelPermission;

    public InvoiceSyncHandler(ManageDraftInvoiceUseCase drafts, InvoiceLifecycleUseCase lifecycle,
                              ModuleProvisioner provisioner, ModulePermissions permissions,
                              ObjectMapper objectMapper, Validator validator, ConvertQuoteUseCase conversions,
                              InvoicePaymentUseCase payments, DocumentAccess access, SendInvoiceUseCase sending,
                              InvoiceSendOutbox outbox, DocumentCancelPermission cancelPermission) {
        this.access = access;
        this.cancelPermission = cancelPermission;
        this.drafts = drafts;
        this.sending = sending;
        this.outbox = outbox;
        this.payments = payments;
        this.conversions = conversions;
        this.lifecycle = lifecycle;
        this.provisioner = provisioner;
        this.permissions = permissions;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Override
    public Set<String> supportedTypes() {
        return PERMISSIONS.keySet();
    }

    @Override
    public String moduleId() {
        return InvoicingModule.ID;
    }

    @Override
    protected void validate(SyncOperation operation) {
        Map<String, Object> p = operation.payload();
        String idField = switch (operation.operationType()) {
            case CREATE -> "id";
            case CONVERT_QUOTE -> "quoteId";
            default -> "invoiceId";
        };
        if (parseUuid(p.get(idField)) == null) {
            throw new IllegalArgumentException("Missing required field: " + idField);
        }
        if (CONVERT_QUOTE.equals(operation.operationType())) {
            // the operation's entity is the quote (as every invoice operation names its invoice)
            if (operation.entityId() != null && !parseUuid(p.get("quoteId")).equals(parseUuid(operation.entityId()))) {
                throw new IllegalArgumentException("entityId does not match quoteId");
            }
            if (p.get("invoiceId") != null && parseUuid(p.get("invoiceId")) == null) {
                throw new IllegalArgumentException("Invalid field: invoiceId");
            }
        }
        if (SEND.equals(operation.operationType()) && operation.entityId() != null
                && !parseUuid(p.get("invoiceId")).equals(parseUuid(operation.entityId()))) {
            throw new IllegalArgumentException("entityId does not match invoiceId");
        }
        if ((ADD_PAYMENT.equals(operation.operationType()) || SETTLE_PAYMENT.equals(operation.operationType()))
                && parseUuid(p.get("paymentId")) == null) {
            throw new IllegalArgumentException("Missing required field: paymentId");
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        String type = operation.operationType();
        requirePermission(type);
        provisioner.ensureProvisioned(tenantId, InvoicingModule.ID);
        Map<String, Object> p = operation.payload();
        // Story 20.14 — a document outside the actor's scope is unknown (404 INVOICE_NOT_FOUND → REJECTED)
        if (CREATE.equals(type)) {
            access.requireCreatable(parseUuid(p.get("id")));
        } else if (CONVERT_QUOTE.equals(type)) {
            access.requireInvoice(parseUuid(p.get("quoteId")));
        } else {
            var known = access.requireInvoice(invoiceId(p));
            if (CANCEL.equals(type)) {
                cancelPermission.require(invoiceId(p), known); // 19.5b — quote: INVOICE_CREATE; invoice: INVOICE_MANAGE
            }
        }
        Invoice result = switch (type) {
            case CREATE -> {
                UUID id = parseUuid(p.get("id"));
                InvoiceRequestDto request = read(p, InvoiceRequestDto.class);
                yield drafts.create(id, content(request), actorId, tenantId);
            }
            case UPDATE -> {
                InvoiceRequestDto request = read(p, InvoiceRequestDto.class);
                yield drafts.update(invoiceId(p), content(request), tenantId);
            }
            case ISSUE -> lifecycle.issue(invoiceId(p), actorId, tenantId);
            case PAY -> {
                PayInvoiceRequestDto request = read(p, PayInvoiceRequestDto.class);
                yield lifecycle.pay(invoiceId(p), request.paymentMethod(), request.paidAt(), actorId, tenantId);
            }
            case CANCEL -> {
                CancelInvoiceRequestDto request = read(p, CancelInvoiceRequestDto.class);
                yield lifecycle.cancel(invoiceId(p), request.reason(), actorId, tenantId);
            }
            case CONVERT_QUOTE -> {
                ConvertQuoteRequestDto request = read(p, ConvertQuoteRequestDto.class);
                yield conversions.convert(parseUuid(p.get("quoteId")), request.invoiceId(),
                        request.expectedUpdatedAt(), actorId, tenantId).invoice();
            }
            case ADD_PAYMENT -> {
                AddInvoicePaymentRequestDto request = read(p, AddInvoicePaymentRequestDto.class);
                yield payments.add(invoiceId(p), request.toCommand(), actorId, tenantId).invoice();
            }
            case SETTLE_PAYMENT -> {
                SettleInvoicePaymentRequestDto request = read(p, SettleInvoicePaymentRequestDto.class);
                yield payments.settle(invoiceId(p), parseUuid(p.get("paymentId")), request.paidAt(),
                        request.method(), actorId, tenantId).invoice();
            }
            case SEND -> {
                SendInvoiceRequestDto request = read(p, SendInvoiceRequestDto.class);
                SendInvoiceUseCase.Queued queued = sending.enqueue(invoiceId(p),
                        SendInvoiceRequestDto.toRequest(request), operation.operationId(), actorId, tenantId);
                outbox.dispatchAfterCommit(tenantId,
                        queued.inserted().stream().map(r -> r.id()).toList());
                yield queued.invoice();
            }
            default -> null;
        };
        if (result == null) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED, null,
                    "UNKNOWN_OPERATION_TYPE");
        }
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                result.id().toString(), null);
    }

    /**
     * Story 20.14 — a rejection keeps its code as {@code reason}; when the refusal has a French message for the user
     * (e.g. « Boutique inconnue ou désactivée », « Vous ne pouvez créer un document que dans votre boutique ») and no
     * other details, {@code conflictData} carries it as {@code {message}} — the same text as the REST error, so the
     * app shows it on the queued document.
     */
    @Override
    protected SyncOperationResult mapDomainException(SyncOperation operation, DomainException e) {
        SyncOperationResult result = super.mapDomainException(operation, e);
        if (result.conflictData() != null || e.getUserMessage() == null || e.getUserMessage().isBlank()) {
            return result;
        }
        return new SyncOperationResult(result.operationId(), result.status(), result.serverEntityId(),
                result.reason(), Map.of("message", e.getUserMessage()));
    }

    /** Same rule as {@code @RequiresPermission} on the matching endpoint (roles of the authenticated actor). */
    private void requirePermission(String type) {
        String required = PERMISSIONS.get(type);
        Set<String> held = permissions.permissionsOf(CurrentActor.authentication());
        if (required == null || !held.contains(required)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Invoice operation not allowed for this role");
        }
    }

    private static UUID invoiceId(Map<String, Object> payload) {
        return parseUuid(payload.get("invoiceId"));
    }

    /**
     * The draft content; a line the domain refuses (quantity, amount) → {@code VALIDATION_ERROR}. Story 20.5: the
     * document terms and line references are validated by the request's constraints first ({@link #read}); a domain
     * refusal ({@code DomainException VALIDATION_ERROR}, French reason) passes through as the rejection.
     */
    private static ManageDraftInvoiceUseCase.DraftContent content(InvoiceRequestDto request) {
        try {
            return request.toDraftContent();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw invalid("Une ligne de la facture est invalide (quantité ou montant hors limites)");
        }
    }

    /**
     * The payload read as the REST request body and validated with its constraints: unreadable → the generic French
     * reason, a violated constraint → its French message (the first one, in a stable order).
     */
    private <T> T read(Map<String, Object> payload, Class<T> type) {
        T value;
        try {
            value = objectMapper.convertValue(payload, type);
        } catch (IllegalArgumentException e) {
            throw invalid("Les données de la facture sont illisibles");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .sorted(Comparator.comparing((ConstraintViolation<T> v) -> v.getPropertyPath().toString()))
                    .map(ConstraintViolation::getMessage)
                    .findFirst().orElse("Données invalides");
            throw invalid(message);
        }
        return value;
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid invoice operation", Map.of("reason", reason));
    }
}
