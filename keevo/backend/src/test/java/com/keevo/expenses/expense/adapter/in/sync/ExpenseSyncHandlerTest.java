package com.keevo.expenses.expense.adapter.in.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.keevo.expenses.expense.application.service.ExpenseAccess;
import com.keevo.expenses.expense.application.usecase.ManageExpenseUseCase;
import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseContent;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
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
        when(permissions.permissionsOf(anyCollection())).thenReturn(held);
    }

    private static SyncOperation op(String type, Map<String, Object> payload) {
        return new SyncOperation(UUID.randomUUID().toString(), type, null, payload, Instant.now());
    }

    private static Expense expense(UUID id) {
        return Expense.create(id, UUID.randomUUID(), ExpenseContent.of("rent", BigDecimal.TEN, null, null, null, null,
                Clock.systemUTC()), ACTOR, Instant.now());
    }

    @Test
    @DisplayName("three operation types owned by the expenses module")
    void types() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrder("CREATE_EXPENSE", "UPDATE_EXPENSE",
                "DELETE_EXPENSE");
        assertThat(handler.moduleId()).isEqualTo("expenses");
    }

    @Test
    @DisplayName("contract keevo/contracts/expense-sync-payloads.json: every queued payload is applied, the refused ones "
            + "are rejected with the documented reason")
    void contractPayloads() throws Exception {
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
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.convertValue(op.path("payload"), Map.class);
            String type = op.path("operationType").asText();
            types.add(type);
            SyncOperationResult r = handler.handle(op(type, payload), ACTOR, TENANT);
            assertThat(r.status()).as("%s: %s", op.path("name").asText(), r.reason())
                    .isEqualTo(SyncOperationStatus.APPLIED);
            assertThat(r.serverEntityId()).isEqualTo(id.toString());
        }
        assertThat(types).isEqualTo(handler.supportedTypes());
        verify(manage).delete(eq(id), eq(ExpenseAccess.OWNER), eq(ACTOR));

        for (JsonNode op : contract.path("rejected")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.convertValue(op.path("payload"), Map.class);
            SyncOperationResult r = handler.handle(op(op.path("operationType").asText(), payload), ACTOR, TENANT);
            assertThat(r.status()).as(op.path("name").asText()).isEqualTo(SyncOperationStatus.REJECTED);
            assertThat(r.reason()).as(op.path("name").asText()).isEqualTo(op.path("reason").asText());
        }
    }

    @Test
    @DisplayName("the create payload reaches the use case as the REST body would (Bref 'description' = label)")
    void createMapping() {
        UUID id = UUID.randomUUID();
        when(manage.create(eq(id), any(), any(), eq(ACTOR), eq(TENANT)))
                .thenReturn(new ManageExpenseUseCase.Created(expense(id), true));
        handler.handle(op("CREATE_EXPENSE", Map.of("id", id.toString(), "amount", 2500.5, "category", "transport",
                "description", "Taxi", "expenseDate", "2026-11-02")), ACTOR, TENANT);
        ArgumentCaptor<ManageExpenseUseCase.Command> command = ArgumentCaptor.forClass(ManageExpenseUseCase.Command.class);
        verify(manage).create(eq(id), command.capture(), eq(ExpenseAccess.OWNER), eq(ACTOR), eq(TENANT));
        assertThat(command.getValue().amount()).isEqualByComparingTo("2500.5");
        assertThat(command.getValue().label()).isEqualTo("Taxi");
        assertThat(command.getValue().expenseDate()).isEqualTo("2026-11-02");
        verify(provisioner).ensureProvisioned(TENANT, "expenses");
    }

    @Test
    @DisplayName("permissions are the endpoints': an employee may not delete, a missing permission → FORBIDDEN")
    void permissions() {
        as("EMPLOYEE", Set.of("EXPENSE_VIEW", "EXPENSE_CREATE"));
        SyncOperationResult r = handler.handle(op("DELETE_EXPENSE", Map.of("expenseId", UUID.randomUUID().toString())),
                ACTOR, TENANT);
        assertThat(r.status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(r.reason()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(manage);
    }

    @Test
    @DisplayName("entityId must name the payload's expense")
    void entityIdMismatch() {
        SyncOperation op = new SyncOperation(UUID.randomUUID().toString(), "DELETE_EXPENSE",
                UUID.randomUUID().toString(), Map.of("expenseId", UUID.randomUUID().toString()), Instant.now());
        assertThat(handler.handle(op, ACTOR, TENANT).status()).isEqualTo(SyncOperationStatus.REJECTED);
        verifyNoInteractions(manage);
    }
}
