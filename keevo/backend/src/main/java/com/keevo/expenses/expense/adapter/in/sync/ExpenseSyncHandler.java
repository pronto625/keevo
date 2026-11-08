package com.keevo.expenses.expense.adapter.in.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.expenses.expense.adapter.in.CurrentExpenseAccess;
import com.keevo.expenses.expense.adapter.in.rest.dto.ExpenseRequestDto;
import com.keevo.expenses.expense.application.usecase.ManageExpenseUseCase;
import com.keevo.expenses.module.ExpensesModule;
import com.keevo.shared.application.port.sync.AbstractSyncOperationHandler;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ErrorMessages;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.application.ModulePermissions;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Story 20.10 — the offline expense operations of the device's sync queue, applied by the same use case as the REST
 * endpoints (same Bref rules, same store scope):
 * <ul>
 *   <li>{@code CREATE_EXPENSE} {@code {id, storeId?, amount, category, expenseDate?, label?, note?, paymentMethod?}}
 *       — the id generated on the device, also the operation's {@code entityId} (required: the last-write-wins check
 *       of later edits compares against this row); idempotent on that id; an absent date = the day the operation
 *       was queued ({@code clientTimestamp}, Africa/Douala), never the day it reaches the server;</li>
 *   <li>{@code UPDATE_EXPENSE} {@code {expenseId, storeId?, amount, category, expenseDate?, label?, note?,
 *       paymentMethod?, expectedUpdatedAt?}} — replaces the content, an absent date keeps the current one (stale
 *       {@code expectedUpdatedAt} → {@code REJECTED OPTIMISTIC_LOCK});</li>
 *   <li>{@code DELETE_EXPENSE} {@code {expenseId}} — final; already deleted → APPLIED.</li>
 * </ul>
 * Payloads are validated with the REST request DTO; permissions are the matching endpoints'. Every rejection carries
 * its French message in {@code conflictData.message} ({@code reason} stays the error code); a malformed operation is
 * rejected with a French {@code reason}. A re-sent operation is answered {@code DUPLICATE} by the push's operation
 * log; {@code UPDATE_/DELETE_EXPENSE} older than the expense's last write are superseded by the push's last-write-wins
 * strategy (on {@code clientTimestamp}) before reaching this handler. The module gate is the push's
 * ({@link #moduleId()}); the module's table is provisioned (lazily) first.
 */
@Component
public class ExpenseSyncHandler extends AbstractSyncOperationHandler {

    public static final String CREATE = "CREATE_EXPENSE";
    public static final String UPDATE = "UPDATE_EXPENSE";
    public static final String DELETE = "DELETE_EXPENSE";

    /** Calendar of the default date of an offline creation (Keevo's business day zone). */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Douala");

    /**
     * French message of an operation refused to the actor's role: expenses are for the OWNER and the MANAGER only
     * (user decision 2026-11-08) — an EMPLOYEE or a VIEWER holds no {@code EXPENSE_*} permission.
     */
    static final String ROLE_REFUSED = "Les dépenses sont réservées au propriétaire et au gérant.";

    /** The permission of each operation: the one of its REST endpoint. */
    private static final Map<String, String> PERMISSIONS = Map.of(
            CREATE, ExpensesModule.EXPENSE_CREATE,
            UPDATE, ExpensesModule.EXPENSE_CREATE,
            DELETE, ExpensesModule.EXPENSE_DELETE);

    private final ManageExpenseUseCase manage;
    private final ModuleProvisioner provisioner;
    private final ModulePermissions permissions;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ExpenseSyncHandler(ManageExpenseUseCase manage, ModuleProvisioner provisioner,
                              ModulePermissions permissions, ObjectMapper objectMapper, Validator validator) {
        this.manage = manage;
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
        return ExpensesModule.ID;
    }

    @Override
    protected void validate(SyncOperation operation) {
        boolean create = CREATE.equals(operation.operationType());
        String idField = create ? "id" : "expenseId";
        UUID id = parseUuid(operation.payload().get(idField));
        if (id == null) {
            throw new IllegalArgumentException("Champ obligatoire manquant ou invalide : " + idField);
        }
        if (create && operation.entityId() == null) {
            throw new IllegalArgumentException("entityId obligatoire : l'identifiant de la dépense");
        }
        if (operation.entityId() != null && !id.equals(parseUuid(operation.entityId()))) {
            throw new IllegalArgumentException("entityId ne correspond pas à " + idField);
        }
    }

    @Override
    protected SyncOperationResult apply(SyncOperation operation, UUID actorId, String tenantId) {
        String type = operation.operationType();
        requirePermission(type);
        provisioner.ensureProvisioned(tenantId, ExpensesModule.ID);
        Map<String, Object> p = operation.payload();
        UUID id = switch (type) {
            case CREATE -> {
                ExpenseRequestDto request = read(p);
                // an absent date is the day the expense was recorded on the device, not the day it syncs
                LocalDate queued = LocalDate.ofInstant(resolveClientTime(null, operation.clientTimestamp()),
                        BUSINESS_ZONE);
                yield manage.create(parseUuid(p.get("id")), request.toCommand().withDefaultDate(queued),
                        CurrentExpenseAccess.get(), actorId, tenantId).expense().id();
            }
            case UPDATE -> {
                ExpenseRequestDto request = read(p);
                yield manage.update(parseUuid(p.get("expenseId")), request.toCommand(), CurrentExpenseAccess.get(),
                        actorId, tenantId).id();
            }
            case DELETE -> {
                UUID expenseId = parseUuid(p.get("expenseId"));
                manage.delete(expenseId, CurrentExpenseAccess.get(), actorId);
                yield expenseId;
            }
            default -> null;
        };
        if (id == null) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED, null,
                    "UNKNOWN_OPERATION_TYPE");
        }
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.APPLIED, id.toString(), null);
    }

    /**
     * {@code reason} = the error code (as every handler), plus the French message for the device in
     * {@code conflictData.message}: the user-facing message of the refusal, else its {@code details.reason}, else the
     * code's catalogue message.
     */
    @Override
    protected SyncOperationResult mapDomainException(SyncOperation op, DomainException e) {
        String reason = e.getErrorCode() != null ? e.getErrorCode().name() : e.getDomainCode();
        Map<String, Object> data = new LinkedHashMap<>();
        if (e.getDetails() != null) {
            data.putAll(e.getDetails());
        }
        data.put("message", message(e, reason));
        return new SyncOperationResult(op.operationId(), SyncOperationStatus.REJECTED, null, reason, data);
    }

    static String message(DomainException e, String reason) {
        if (e.getUserMessage() != null) {
            return e.getUserMessage();
        }
        if (e.getDetails() != null && e.getDetails().get("reason") instanceof String text && !text.isBlank()) {
            return text;
        }
        return ErrorMessages.of(reason, "Opération refusée");
    }

    /** Same rule as {@code @RequiresPermission} on the matching endpoint (roles of the authenticated actor). */
    private void requirePermission(String type) {
        String required = PERMISSIONS.get(type);
        Set<String> held = permissions.permissionsOf(ModulePermissions.rolesOf(CurrentActor.authentication()));
        if (required == null || !held.contains(required)) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, ROLE_REFUSED);
        }
    }

    /**
     * The payload read as the REST request body and validated with its constraints: unreadable → the generic French
     * reason, a violated constraint → its French message (the first one, in a stable order).
     */
    private ExpenseRequestDto read(Map<String, Object> payload) {
        ExpenseRequestDto value;
        try {
            value = objectMapper.convertValue(payload, ExpenseRequestDto.class);
        } catch (IllegalArgumentException e) {
            throw invalid("Les données de la dépense sont illisibles");
        }
        Set<ConstraintViolation<ExpenseRequestDto>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .sorted(Comparator.comparing((ConstraintViolation<ExpenseRequestDto> v) ->
                            v.getPropertyPath().toString()).thenComparing(ConstraintViolation::getMessage))
                    .map(ConstraintViolation::getMessage)
                    .findFirst().orElse("Données invalides");
            throw invalid(message);
        }
        return value;
    }

    private static DomainException invalid(String reason) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, reason);
    }
}
