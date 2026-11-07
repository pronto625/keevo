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
 * Story 20.10 — the offline expense operations of the device's sync queue, applied by the same use case as the REST
 * endpoints (same Bref rules, same store scope):
 * <ul>
 *   <li>{@code CREATE_EXPENSE} {@code {id, storeId?, amount, category, expenseDate?, label?, note?, paymentMethod?}}
 *       — the id generated on the device; idempotent on that id (sent again → the expense already created);</li>
 *   <li>{@code UPDATE_EXPENSE} {@code {expenseId, storeId?, amount, category, expenseDate?, label?, note?,
 *       paymentMethod?, expectedUpdatedAt?}} — replaces the content (stale {@code expectedUpdatedAt} →
 *       {@code REJECTED OPTIMISTIC_LOCK});</li>
 *   <li>{@code DELETE_EXPENSE} {@code {expenseId}} — final; already deleted → APPLIED.</li>
 * </ul>
 * Payloads are validated with the REST request DTO (French reason in {@code VALIDATION_ERROR}); permissions are the
 * matching endpoints' ({@code FORBIDDEN} otherwise). A re-sent operation is answered {@code DUPLICATE} by the push's
 * operation log; {@code UPDATE_/DELETE_EXPENSE} older than the expense's last write are superseded by the push's
 * last-write-wins strategy (on {@code clientTimestamp}) before reaching this handler. The module gate is the push's
 * ({@link #moduleId()}); the module's table is provisioned (lazily) first.
 */
@Component
public class ExpenseSyncHandler extends AbstractSyncOperationHandler {

    public static final String CREATE = "CREATE_EXPENSE";
    public static final String UPDATE = "UPDATE_EXPENSE";
    public static final String DELETE = "DELETE_EXPENSE";

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
        String idField = CREATE.equals(operation.operationType()) ? "id" : "expenseId";
        UUID id = parseUuid(operation.payload().get(idField));
        if (id == null) {
            throw new IllegalArgumentException("Missing required field: " + idField);
        }
        if (operation.entityId() != null && !id.equals(parseUuid(operation.entityId()))) {
            throw new IllegalArgumentException("entityId does not match " + idField);
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
                yield manage.create(parseUuid(p.get("id")), request.toCommand(), CurrentExpenseAccess.get(), actorId,
                        tenantId).expense().id();
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

    /** Same rule as {@code @RequiresPermission} on the matching endpoint (roles of the authenticated actor). */
    private void requirePermission(String type) {
        String required = PERMISSIONS.get(type);
        Set<String> held = permissions.permissionsOf(ModulePermissions.rolesOf(CurrentActor.authentication()));
        if (required == null || !held.contains(required)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Expense operation not allowed for this role");
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
        return new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid expense operation", Map.of("reason", reason));
    }
}
