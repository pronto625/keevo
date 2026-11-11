package com.keevo.expenses.expense.adapter.in.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.keevo.expenses.expense.adapter.in.rest.dto.ExpenseResponseDto;
import com.keevo.expenses.expense.application.service.ExpenseAccess;
import com.keevo.expenses.expense.application.usecase.ManageExpenseUseCase;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseContent;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.application.ModulePermissions;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExpenseSyncHandler — offline expense operations (Story 20.10)")
class ExpenseSyncHandlerTest {

    private static final String TENANT = "kv_test01";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Set<String> ALL = Set.of("EXPENSE_VIEW", "EXPENSE_CREATE", "EXPENSE_DELETE");

    private final ManageExpenseUseCase manage = mock(ManageExpenseUseCase.class);
    private final ModuleProvisioner provisioner = mock(ModuleProvisioner.class);
    private final ModulePermissions permissions = mock(ModulePermissions.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private ExpenseSyncHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExpenseSyncHandler(manage, provisioner, permissions, objectMapper, validator);
        as("OWNER", ALL);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void as(String role, Set<String> held) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        when(permissions.permissionsOf(org.mockito.ArgumentMatchers.<org.springframework.security.core.Authentication>any())).thenReturn(held);
    }

    private static SyncOperation op(String type, String entityId, Map<String, Object> payload, Instant at) {
        return new SyncOperation(UUID.randomUUID().toString(), type, entityId, payload, at);
    }

    private static SyncOperation op(String type, String entityId, Map<String, Object> payload) {
        return op(type, entityId, payload, Instant.now());
    }

    private static Expense expense(UUID id) {
        return Expense.create(id, UUID.randomUUID(), ExpenseContent.of("rent", BigDecimal.TEN, null, null, null, null,
                Clock.systemUTC()), ACTOR, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    @Test
    @DisplayName("three operation types owned by the expenses module")
    void types() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("CREATE_EXPENSE", "UPDATE_EXPENSE",
                "DELETE_EXPENSE");
        assertThat(handler.moduleId()).isEqualTo("expenses");
    }

    @Test
    @DisplayName("contract keevo/contracts/expense-sync-payloads.json: queued payloads applied; refusals with their code "
            + "and French message; pull row and tombstone shapes; result examples")
    void contract() throws Exception {
        JsonNode contract = objectMapper.readTree(Path.of("..", "contracts", "expense-sync-payloads.json").toFile());
        UUID id = UUID.fromString(contract.path("expenseId").asText());
        when(manage.create(eq(id), any(), any(), eq(ACTOR), eq(TENANT)))
                .thenReturn(new ManageExpenseUseCase.Created(expense(id), true));
        when(manage.update(eq(id), any(), any(), eq(ACTOR), eq(TENANT))).thenReturn(expense(id));
        assertThat(objectMapper.convertValue(contract.path("categories"), List.class))
                .containsExactlyElementsOf(java.util.Arrays.stream(ExpenseCategory.values())
                        .map(ExpenseCategory::key).toList());

        Set<String> types = new HashSet<>();
        for (JsonNode op : contract.path("operations")) {
            String type = op.path("operationType").asText();
            types.add(type);
            SyncOperationResult r = handler.handle(op(type, op.path("entityId").asText(null), map(op.path("payload"))),
                    ACTOR, TENANT);
            assertThat(r.status()).as("%s: %s", op.path("name").asText(), r.reason())
                    .isEqualTo(SyncOperationStatus.APPLIED);
            assertThat(r.serverEntityId()).isEqualTo(id.toString());
        }
        assertThat(types).isEqualTo(handler.supportedTypes());
        verify(manage).delete(eq(id), eq(ExpenseAccess.OWNER), eq(ACTOR));

        for (JsonNode op : contract.path("rejected")) {
            String name = op.path("name").asText();
            reset(manage);
            as(op.path("role").asText("OWNER"), op.has("role") ? Set.of() : ALL);
            switch (name) {
                case "forbidden" -> when(manage.update(eq(id), any(), any(), any(), any())).thenThrow(
                        DomainException.userFacing(ErrorCode.FORBIDDEN, op.path("message").asText()));
                case "stale" -> when(manage.update(eq(id), any(), any(), any(), any())).thenThrow(
                        DomainException.userFacing(ErrorCode.OPTIMISTIC_LOCK, ManageExpenseUseCase.STALE_EXPENSE,
                                Map.of("currentUpdatedAt", "2026-11-07T08:15:30.123456Z")));
                case "notFound" -> doThrow(DomainException.userFacing(ErrorCode.EXPENSE_NOT_FOUND,
                        "Dépense introuvable")).when(manage).delete(eq(id), any(), any());
                default -> { }
            }
            SyncOperationResult r = handler.handle(op(op.path("operationType").asText(),
                    op.path("entityId").asText(null), map(op.path("payload"))), ACTOR, TENANT);
            assertThat(r.status()).as(name).isEqualTo(SyncOperationStatus.REJECTED);
            assertThat(r.reason()).as(name).isEqualTo(op.path("reason").asText());
            if (op.has("message")) {
                assertThat(r.conflictData()).as(name).containsEntry("message", op.path("message").asText());
            }
        }
        as("OWNER", ALL);

        // the pull row has exactly the REST response fields; the tombstone exactly {id, deleted, updatedAt}
        Map<String, Object> rest = objectMapper.convertValue(ExpenseResponseDto.fromDomain(expense(id), "Boutique"),
                Map.class);
        assertThat(map(contract.path("pull").path("row")).keySet()).containsExactlyInAnyOrderElementsOf(rest.keySet());
        Iterator<String> tombstone = contract.path("pull").path("tombstone").fieldNames();
        List<String> fields = new java.util.ArrayList<>();
        tombstone.forEachRemaining(fields::add);
        assertThat(fields).containsExactly("id", "deleted", "updatedAt");
        assertThat(contract.path("results").path("superseded").path("status").asText()).isEqualTo("APPLIED");
        assertThat(contract.path("results").path("superseded").path("reason").asText()).isEqualTo("LWW_SUPERSEDED");
        assertThat(contract.path("results").path("rejected").path("conflictData").path("message").asText())
                .isEqualTo("Dépense introuvable");
    }

    @Test
    @DisplayName("an offline creation without date takes the day it was queued (Africa/Douala), not the sync day")
    void createDefaultDate() {
        UUID id = UUID.randomUUID();
        when(manage.create(eq(id), any(), any(), eq(ACTOR), eq(TENANT)))
                .thenReturn(new ManageExpenseUseCase.Created(expense(id), true));
        // 2026-03-04T23:30Z = 2026-03-05 00:30 in Douala
        handler.handle(op("CREATE_EXPENSE", id.toString(), Map.of("id", id.toString(), "amount", 2500.5,
                "category", "transport", "description", "Taxi"), Instant.parse("2026-03-04T23:30:00Z")), ACTOR, TENANT);
        ArgumentCaptor<ManageExpenseUseCase.Command> command = ArgumentCaptor.forClass(ManageExpenseUseCase.Command.class);
        verify(manage).create(eq(id), command.capture(), eq(ExpenseAccess.OWNER), eq(ACTOR), eq(TENANT));
        assertThat(command.getValue().defaultDate()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(command.getValue().amount()).isEqualByComparingTo("2500.5");
        assertThat(command.getValue().label()).isEqualTo("Taxi");
        verify(provisioner).ensureProvisioned(TENANT, "expenses");
    }

    @Test
    @DisplayName("a refusal reaches the device with its French message (domain rule, role)")
    void frenchReasons() {
        UUID id = UUID.randomUUID();
        when(manage.create(eq(id), any(), any(), any(), any())).thenThrow(DomainException.userFacing(
                ErrorCode.VALIDATION_ERROR, "La date de la dépense ne peut pas dépasser demain",
                Map.of("field", "expenseDate")));
        SyncOperationResult r = handler.handle(op("CREATE_EXPENSE", id.toString(), Map.of("id", id.toString(),
                "amount", 10, "category", "other", "expenseDate", "2099-01-01")), ACTOR, TENANT);
        assertThat(r.reason()).isEqualTo("VALIDATION_ERROR");
        assertThat(r.conflictData()).containsEntry("message", "La date de la dépense ne peut pas dépasser demain")
                .containsEntry("field", "expenseDate");

    }

    @Test
    @DisplayName("user decision 2026-11-08: an EMPLOYEE or a VIEWER is REJECTED FORBIDDEN on every operation (French "
            + "message, use case never called); a MANAGER's operations apply")
    void roles() {
        UUID id = UUID.randomUUID();
        List<SyncOperation> ops = List.of(
                op("CREATE_EXPENSE", id.toString(), Map.of("id", id.toString(), "amount", 10, "category", "other")),
                op("UPDATE_EXPENSE", id.toString(), Map.of("expenseId", id.toString(), "amount", 10,
                        "category", "other")),
                op("DELETE_EXPENSE", id.toString(), Map.of("expenseId", id.toString())));
        for (String role : List.of("EMPLOYEE", "VIEWER")) {
            as(role, Set.of());
            for (SyncOperation o : ops) {
                SyncOperationResult r = handler.handle(o, ACTOR, TENANT);
                assertThat(r.status()).as("%s %s", role, o.operationType()).isEqualTo(SyncOperationStatus.REJECTED);
                assertThat(r.reason()).isEqualTo("FORBIDDEN");
                assertThat(r.conflictData()).containsEntry("message",
                        "Les dépenses sont réservées au propriétaire et au gérant.");
            }
        }
        verifyNoInteractions(manage);

        as("MANAGER", ALL);
        when(manage.create(eq(id), any(), any(), eq(ACTOR), eq(TENANT)))
                .thenReturn(new ManageExpenseUseCase.Created(expense(id), true));
        when(manage.update(eq(id), any(), any(), eq(ACTOR), eq(TENANT))).thenReturn(expense(id));
        for (SyncOperation o : ops) {
            assertThat(handler.handle(o, ACTOR, TENANT).status()).as("MANAGER %s", o.operationType())
                    .isEqualTo(SyncOperationStatus.APPLIED);
        }
        verify(manage).delete(eq(id), any(ExpenseAccess.class), eq(ACTOR));
    }

    @Test
    @DisplayName("entityId must name the payload's expense; required on a creation")
    void entityId() {
        UUID id = UUID.randomUUID();
        SyncOperationResult mismatch = handler.handle(op("DELETE_EXPENSE", UUID.randomUUID().toString(),
                Map.of("expenseId", id.toString())), ACTOR, TENANT);
        assertThat(mismatch.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(mismatch.reason()).isEqualTo("entityId ne correspond pas à expenseId");
        SyncOperationResult missing = handler.handle(op("CREATE_EXPENSE", null,
                Map.of("id", id.toString(), "amount", 1, "category", "other")), ACTOR, TENANT);
        assertThat(missing.reason()).isEqualTo("entityId obligatoire : l'identifiant de la dépense");
        verifyNoInteractions(manage);
    }
}
