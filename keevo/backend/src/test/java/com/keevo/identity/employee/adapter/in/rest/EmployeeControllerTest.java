package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeStatus;
import com.keevo.identity.employee.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @InjectMocks EmployeeController employeeController;

    MockMvc mockMvc;
    UUID actorId;
    UUID storeId;
    UUID employeeId;
    Employee sampleEmployee;

    @BeforeEach
    void setUp() {
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
                .andExpect(jsonPath("$.data.employee.firstName").value("Loïc"));
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
                .andExpect(jsonPath("$.data[0].firstName").value("Loïc"));
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
                .andExpect(jsonPath("$.data.firstName").value("Loïc"));
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
}
