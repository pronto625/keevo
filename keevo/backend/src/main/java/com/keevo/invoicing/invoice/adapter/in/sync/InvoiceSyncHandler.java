package com.keevo.invoicing.invoice.adapter.in.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.CancelInvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceRequestDto;
import com.keevo.invoicing.invoice.adapter.in.rest.dto.PayInvoiceRequestDto;
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
 *   <li>{@code CANCEL_INVOICE} {@code {invoiceId, reason}} (owner).</li>
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

    /** The permission of each operation: the one of its REST endpoint. */
    private static final Map<String, String> PERMISSIONS = Map.of(
            CREATE, InvoicingModule.INVOICE_CREATE,
            UPDATE, InvoicingModule.INVOICE_CREATE,
            ISSUE, InvoicingModule.INVOICE_SEND,
            PAY, InvoicingModule.INVOICE_SEND,
            CANCEL, InvoicingModule.INVOICE_MANAGE);

    private final ManageDraftInvoiceUseCase drafts;
    private final InvoiceLifecycleUseCase lifecycle;
    private final ModuleProvisioner provisioner;
    private final ModulePermissions permissions;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public InvoiceSyncHandler(ManageDraftInvoiceUseCase drafts, InvoiceLifecycleUseCase lifecycle,
                              ModuleProvisioner provisioner, ModulePermissions permissions,
                              ObjectMapper objectMapper, Validator validator) {
        this.drafts = drafts;
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
        String idField = CREATE.equals(operation.operationType()) ? "id" : "invoiceId";
        if (parseUuid(p.get(idField)) == null) {
            throw new IllegalArgumentException("Missing required field: " + idField);
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        String type = operation.operationType();
        requirePermission(type);
        provisioner.ensureProvisioned(tenantId, InvoicingModule.ID);
        Map<String, Object> p = operation.payload();
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
            default -> null;
        };
        if (result == null) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED, null,
                    "UNKNOWN_OPERATION_TYPE");
        }
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED,
                result.id().toString(), null);
    }

    /** Same rule as {@code @RequiresPermission} on the matching endpoint (roles of the authenticated actor). */
    private void requirePermission(String type) {
        String required = PERMISSIONS.get(type);
        Set<String> held = permissions.permissionsOf(ModulePermissions.rolesOf(CurrentActor.authentication()));
        if (required == null || !held.contains(required)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Invoice operation not allowed for this role");
        }
    }

    private static UUID invoiceId(Map<String, Object> payload) {
        return parseUuid(payload.get("invoiceId"));
    }

    /** The draft content; a line the domain refuses (quantity, amount) → {@code VALIDATION_ERROR}. */
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
