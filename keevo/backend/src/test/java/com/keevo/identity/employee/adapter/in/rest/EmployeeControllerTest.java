package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeActivity;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.*;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * EmployeeControllerTest — TDD RED tests (Story 3.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeController")
class EmployeeControllerTest {

    @Mock CreateEmployeeUseCase createEmployeeUseCase;
    @Mock ListEmployeesUseCase listEmployeesUseCase;
    @Mock ReassignStoreUseCase reassignStoreUseCase;
    @Mock DeactivateEmployeeUseCase deactivateEmployeeUseCase;
    @Mock ReactivateEmployeeUseCase reactivateEmployeeUseCase;
    @Mock RegeneratePasswordUseCase regeneratePasswordUseCase;
    @Mock UpdateEmployeeUseCase updateEmployeeUseCase;
    @Mock ChangeEmployeeRoleUseCase changeEmployeeRoleUseCase;
    @Mock SetEmployeePasswordUseCase setEmployeePasswordUseCase;
    @Mock EmployeeRoleResolver employeeRoleResolver;
    @Mock GetEmployeeActivityUseCase getEmployeeActivityUseCase;
    @Mock EmployeeRepository employeeRepository;
    @Mock com.keevo.identity.employee.domain.port.in.MembershipInvitationUseCase invitationUseCase;

    EmployeeController employeeController;

    MockMvc mockMvc;
    UUID actorId;
    UUID storeId;
    UUID employeeId;
    Employee sampleEmployee;

    @BeforeEach
    void setUp() {
        employeeController = new EmployeeController(createEmployeeUseCase, listEmployeesUseCase, reassignStoreUseCase,
                deactivateEmployeeUseCase, reactivateEmployeeUseCase, regeneratePasswordUseCase, updateEmployeeUseCase,
                changeEmployeeRoleUseCase, setEmployeePasswordUseCase, employeeRoleResolver, getEmployeeActivityUseCase,
                new EmployeeTeamScope(employeeRepository, employeeRoleResolver,
                        com.keevo.shared.infrastructure.security.ShippedActorPermissions.get()), invitationUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(employeeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        sampleEmployee = new Employee(employeeId, UUID.randomUUID(), storeId,
                "Loïc", "Nkoulou", EmployeeStatus.ACTIVE, true, Instant.now());
        enrichWith(new EmployeeRoleResolver.Enrichment("EMPLOYEE", "+237690000001", null));
    }

    /** Every requested user gets the same enrichment (one bulk call per request). */
    private void enrichWith(EmployeeRoleResolver.Enrichment enrichment) {
        lenient().doAnswer(inv -> {
            java.util.Map<UUID, EmployeeRoleResolver.Enrichment> map = new java.util.HashMap<>();
            for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                map.put((UUID) id, enrichment);
            }
            return map;
        }).when(employeeRoleResolver).resolveAll(any());
    }

    @Test
    @DisplayName("19.4 GET /api/v1/employees enriches the whole list with ONE bulk call (no per-employee lookup)")
    void listEmployees_enrichesInOneBulkCall() throws Exception {
        Employee second = new Employee(UUID.randomUUID(), UUID.randomUUID(), storeId,
                "Awa", "Kamga", EmployeeStatus.ACTIVE, false, Instant.now());
        Employee third = new Employee(UUID.randomUUID(), UUID.randomUUID(), storeId,
                "Jean", "Ndi", EmployeeStatus.ACTIVE, false, Instant.now());
        when(listEmployeesUseCase.execute(any())).thenReturn(List.of(sampleEmployee, second, third));

        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[2].phoneNumber").value("+237690000001"));

        verify(employeeRoleResolver, times(1)).resolveAll(any());
        verify(employeeRoleResolver, never()).resolveRole(any());
    }

    @Test
    @DisplayName("POST /api/v1/employees returns 201 with tempPasswordField")
    void createEmployee_returns201_withTempPasswordField() throws Exception {
        when(createEmployeeUseCase.execute(any())).thenReturn(
                new CreateEmployeeResult(sampleEmployee, "TmpPass12345"));

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Loïc","lastName":"Nkoulou","phoneNumber":"+237690000001","storeId":"%s"}
                                """.formatted(storeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.temporaryPassword").value("TmpPass12345"))
                .andExpect(jsonPath("$.data.existingAccount").value(false)) // 19.6 — always present
                .andExpect(jsonPath("$.data.invitationPending").value(false)) // 19.6b — always present
                .andExpect(jsonPath("$.data.employee.firstName").value("Loïc"));
    }

    @Test
    @DisplayName("19.6b POST /api/v1/employees with an existing account → 202, employee null, temporaryPassword \"\" "
            + "(never null: V1 reads a String), existingAccount and invitationPending true")
    void createEmployee_existingAccount_invitation() throws Exception {
        when(createEmployeeUseCase.execute(any())).thenReturn(CreateEmployeeResult.pendingInvitation());

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Loïc","lastName":"Nkoulou","phoneNumber":"+237690000001","storeId":"%s"}
                                """.formatted(storeId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.employee").doesNotExist())
                .andExpect(jsonPath("$.data.temporaryPassword").value(""))
                .andExpect(jsonPath("$.data.existingAccount").value(true))
                .andExpect(jsonPath("$.data.invitationPending").value(true));
        verify(employeeRoleResolver, never()).resolveAll(any());
    }

    @Test
    @DisplayName("19.6b GET /api/v1/employees/invitations (owner: whole company) and DELETE cancel → 204")
    void invitations_listAndCancel() throws Exception {
        var inv = com.keevo.identity.employee.domain.model.MembershipInvitation.pending(UUID.randomUUID(),
                UUID.randomUUID(), "VIEWER", storeId, "Awa", "Mbarga",
                com.keevo.identity.employee.domain.model.MembershipInvitation.IdentifierKind.USERNAME, "awa.k", actorId,
                Instant.now(), Instant.now().plusSeconds(3600));
        when(invitationUseCase.listForCompany(null)).thenReturn(List.of(inv));

        mockMvc.perform(get("/api/v1/employees/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].firstName").value("Awa"))
                .andExpect(jsonPath("$.data[0].roleLabel").value("Lecteur"))
                .andExpect(jsonPath("$.data[0].identifierKind").value("USERNAME"))
                .andExpect(jsonPath("$.data[0].identifier").value("awa.k"));

        mockMvc.perform(delete("/api/v1/employees/invitations/" + inv.id()))
                .andExpect(status().isNoContent());
        verify(invitationUseCase).cancel(inv.id(), null);
    }

    @Test
    @DisplayName("19.4 POST /api/v1/employees with a username only → 201, phoneNumber \"\" (19.1), username, command carries it")
    void createEmployee_usernameOnly_returns201() throws Exception {
        when(createEmployeeUseCase.execute(any())).thenReturn(
                new CreateEmployeeResult(sampleEmployee, "TmpPass12345"));
        enrichWith(new EmployeeRoleResolver.Enrichment("EMPLOYEE", "", "awa_k"));

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Awa","lastName":"Kamga","username":"awa_k","storeId":"%s"}
                                """.formatted(storeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.temporaryPassword").value("TmpPass12345"))
                .andExpect(jsonPath("$.data.employee.username").value("awa_k"))
                .andExpect(jsonPath("$.data.employee.phoneNumber").value(""));

        var captor = org.mockito.ArgumentCaptor.forClass(CreateEmployeeCommand.class);
        verify(createEmployeeUseCase).execute(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().username()).isEqualTo("awa_k");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().phoneNumber()).isNull();
    }

    @Test
    @DisplayName("19.4 POST /api/v1/employees → 409 USERNAME_ALREADY_EXISTS with the French message")
    void createEmployee_usernameTaken_returns409() throws Exception {
        when(createEmployeeUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.USERNAME_ALREADY_EXISTS));

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Awa","lastName":"Kamga","username":"AWA_K","storeId":"%s"}
                                """.formatted(storeId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("USERNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error").value("Cet identifiant est déjà utilisé"));
    }

    @Test
    @DisplayName("POST /api/v1/employees returns 403 when plan limit exceeded")
    void createEmployee_returns403_whenPlanLimitExceeded() throws Exception {
        when(createEmployeeUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "employees limit reached"));

        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Test","lastName":"User","phoneNumber":"+237690000002","storeId":"%s"}
                                """.formatted(storeId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("PLAN_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("POST /api/v1/employees returns 403 when not OWNER")
    void createEmployee_returns403_whenNotOwner() throws Exception {
        // Set EMPLOYEE role (not OWNER)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));

        // requireOwnerOrForbid() fires before use case is invoked
        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Test","lastName":"User","phoneNumber":"+237690000003","storeId":"%s"}
                                """.formatted(storeId)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(createEmployeeUseCase);
    }

    @Test
    @DisplayName("POST /api/v1/employees returns 403 without auth context (OWNER guard fires)")
    void createEmployee_returns403_withoutJwt() throws Exception {
        SecurityContextHolder.clearContext();
        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Test","lastName":"User","phoneNumber":"+237690000004","storeId":"%s"}
                                """.formatted(storeId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/employees returns 200 with employee list")
    void listEmployees_returns200_withEmployeeList() throws Exception {
        when(listEmployeesUseCase.execute(any())).thenReturn(List.of(sampleEmployee));

        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].firstName").value("Loïc"))
                .andExpect(jsonPath("$.data[0].role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data[0].phoneNumber").value("+237690000001"));
    }

    @Test
    @DisplayName("PATCH /api/v1/employees/{id}/store returns 200 on success")
    void reassignStore_returns200_onSuccess() throws Exception {
        UUID newStoreId = UUID.randomUUID();
        when(reassignStoreUseCase.execute(any())).thenReturn(sampleEmployee.withStoreId(newStoreId));

        mockMvc.perform(patch("/api/v1/employees/" + employeeId + "/store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":"%s"}
                                """.formatted(newStoreId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(newStoreId.toString()));
    }

    @Test
    @DisplayName("PATCH /api/v1/employees/{id}/deactivate returns 200 on success")
    void deactivateEmployee_returns200_onSuccess() throws Exception {
        doNothing().when(deactivateEmployeeUseCase).execute(any());

        mockMvc.perform(patch("/api/v1/employees/" + employeeId + "/deactivate"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /api/v1/employees/{id}/reactivate returns 200 on success")
    void reactivateEmployee_returns200_onSuccess() throws Exception {
        doNothing().when(reactivateEmployeeUseCase).execute(any());

        mockMvc.perform(patch("/api/v1/employees/" + employeeId + "/reactivate"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/employees/{id}/regenerate-password returns 200 with new temp password")
    void regeneratePassword_returns200_withNewTempPassword() throws Exception {
        when(regeneratePasswordUseCase.execute(any())).thenReturn(
                new CreateEmployeeResult(sampleEmployee, "NewTmp12345"));

        mockMvc.perform(post("/api/v1/employees/" + employeeId + "/regenerate-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temporaryPassword").value("NewTmp12345"));
    }

    // ── Story 14.11 — new endpoint tests ─────────────────────────────────

    private void setEmployeeRole() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }

    @Test
    @DisplayName("PATCH /employees/{id} returns 403 for EMPLOYEE (not OWNER)")
    void shouldReturn403ForEmployeeOnProfileUpdate() throws Exception {
        setEmployeeRole();
        mockMvc.perform(patch("/api/v1/employees/" + employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"NewName"}
                                """))
                .andExpect(status().isForbidden());
        verifyNoInteractions(updateEmployeeUseCase);
    }

    @Test
    @DisplayName("PATCH /employees/{id}/role returns 403 for EMPLOYEE (not OWNER)")
    void shouldReturn403ForEmployeeOnRoleChange() throws Exception {
        setEmployeeRole();
        mockMvc.perform(patch("/api/v1/employees/" + employeeId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}
                                """))
                .andExpect(status().isForbidden());
        verifyNoInteractions(changeEmployeeRoleUseCase);
    }

    @Test
    @DisplayName("POST /employees/{id}/password returns 403 for EMPLOYEE (not OWNER)")
    void shouldReturn403ForEmployeeOnPasswordSet() throws Exception {
        setEmployeeRole();
        mockMvc.perform(post("/api/v1/employees/" + employeeId + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"NewPass123"}
                                """))
                .andExpect(status().isForbidden());
        verifyNoInteractions(setEmployeePasswordUseCase);
    }

    @Test
    @DisplayName("PATCH /employees/{id} returns 409 on duplicate phone number")
    void shouldReturn409OnDuplicatePhoneOnUpdate() throws Exception {
        doThrow(new DomainException(ErrorCode.PHONE_ALREADY_REGISTERED, "Phone already taken"))
                .when(updateEmployeeUseCase).execute(any());

        mockMvc.perform(patch("/api/v1/employees/" + employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumber":"+237690000999"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("PATCH /employees/{id} returns 200 on success")
    void shouldUpdateEmployeeProfileSuccessfully() throws Exception {
        when(updateEmployeeUseCase.execute(any())).thenReturn(sampleEmployee);

        mockMvc.perform(patch("/api/v1/employees/" + employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"NewName","phoneNumber":"+237690000001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Loïc"))
                .andExpect(jsonPath("$.data.phoneNumber").value("+237690000001"));
    }

    @Test
    @DisplayName("PATCH /employees/{id} returns 400 on empty body")
    void shouldRejectEmptyPatchBody() throws Exception {
        mockMvc.perform(patch("/api/v1/employees/" + employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH /employees/{id} returns 422 on empty firstName (@Size validation)")
    void shouldRejectEmptyFirstName() throws Exception {
        mockMvc.perform(patch("/api/v1/employees/" + employeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":""}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("PATCH /employees/{id}/role returns 200 on success")
    void shouldChangeRoleSuccessfully() throws Exception {
        doNothing().when(changeEmployeeRoleUseCase).execute(any());

        mockMvc.perform(patch("/api/v1/employees/" + employeeId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /employees/{id}/password returns 200 on success")
    void shouldSetPasswordSuccessfully() throws Exception {
        doNothing().when(setEmployeePasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/employees/" + employeeId + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"NewPass123"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /employees/{id}/password returns 422 on weak password")
    void shouldReturn422OnWeakPassword() throws Exception {
        doThrow(new DomainException(ErrorCode.VALIDATION_FAILED, "Weak password"))
                .when(setEmployeePasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/employees/" + employeeId + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"short"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /employees/{id}/password returns 403 when owner sets own password (D2)")
    void shouldReturn403OnSelfPasswordSet() throws Exception {
        doThrow(new DomainException(ErrorCode.CANNOT_SET_OWN_PASSWORD, "Cannot set own password"))
                .when(setEmployeePasswordUseCase).execute(any());

        mockMvc.perform(post("/api/v1/employees/" + employeeId + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"NewPass123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("CANNOT_SET_OWN_PASSWORD"));
    }

    // ── v1s-14-6 — FR67 activity ─────────────────────────────────────────

    private EmployeeActivity sampleActivity(boolean inactive) {
        return new EmployeeActivity(employeeId, sampleEmployee.getUserId(),
                Instant.parse("2026-10-12T08:00:00Z"), Instant.parse("2026-10-13T09:00:00Z"),
                2, 10_000L, 3, 13_000L, true, inactive);
    }

    @Test
    @DisplayName("GET /employees/{id}/activity returns 200 with the activity for an OWNER")
    void shouldReturnEmployeeActivityForOwner() throws Exception {
        when(getEmployeeActivityUseCase.forEmployee(employeeId)).thenReturn(sampleActivity(false));

        mockMvc.perform(get("/api/v1/employees/" + employeeId + "/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeId").value(employeeId.toString()))
                .andExpect(jsonPath("$.data.todaySalesCount").value(2))
                .andExpect(jsonPath("$.data.todaySalesAmount").value(10000))
                .andExpect(jsonPath("$.data.weekSalesCount").value(3))
                .andExpect(jsonPath("$.data.weekSalesAmount").value(13000))
                .andExpect(jsonPath("$.data.lastLoginAt").exists())
                .andExpect(jsonPath("$.data.activeSession").value(true))
                .andExpect(jsonPath("$.data.inactive").value(false));
    }

    @Test
    @DisplayName("GET /employees/activity returns 200 with one entry per employee for an OWNER")
    void shouldReturnAllEmployeesActivityForOwner() throws Exception {
        when(getEmployeeActivityUseCase.forAll()).thenReturn(List.of(sampleActivity(true)));

        mockMvc.perform(get("/api/v1/employees/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].inactive").value(true));
    }

    @Test
    @DisplayName("GET /employees/{id}/activity and /employees/activity return 403 for an EMPLOYEE")
    void shouldReturn403ForEmployee() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));

        mockMvc.perform(get("/api/v1/employees/" + employeeId + "/activity"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/employees/activity"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(getEmployeeActivityUseCase);
    }

    @Test
    @DisplayName("GET /employees/{id}/activity returns 404 EMPLOYEE_NOT_FOUND for an unknown id")
    void shouldReturn404ForUnknownEmployeeActivity() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(getEmployeeActivityUseCase.forEmployee(unknown))
                .thenThrow(new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/employees/" + unknown + "/activity"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("EMPLOYEE_NOT_FOUND"));
    }
}
