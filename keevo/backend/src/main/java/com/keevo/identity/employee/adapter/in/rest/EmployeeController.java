package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.identity.employee.adapter.in.rest.dto.*;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.model.EmployeeActivity;
import com.keevo.identity.employee.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * EmployeeController — REST adapter for employee management (Story 3.5).
 *
 * <p>All endpoints require JWT authentication. OWNER on every member; Story 19.5 — a MANAGER lists, creates and
 * manages the {@code EMPLOYEE}/{@code VIEWER} members of his own store ({@link EmployeeTeamScope}); role change and
 * store reassignment stay OWNER-only.
 */
@Tag(name = "Employees", description = "Employee management")
@EntitlementExempt("core: employés (plafond du plan)")
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final CreateEmployeeUseCase createEmployeeUseCase;
    private final ListEmployeesUseCase listEmployeesUseCase;
    private final ReassignStoreUseCase reassignStoreUseCase;
    private final DeactivateEmployeeUseCase deactivateEmployeeUseCase;
    private final ReactivateEmployeeUseCase reactivateEmployeeUseCase;
    private final RegeneratePasswordUseCase regeneratePasswordUseCase;
    private final UpdateEmployeeUseCase updateEmployeeUseCase;
    private final ChangeEmployeeRoleUseCase changeEmployeeRoleUseCase;
    private final SetEmployeePasswordUseCase setEmployeePasswordUseCase;
    private final EmployeeRoleResolver employeeRoleResolver;
    private final GetEmployeeActivityUseCase getEmployeeActivityUseCase;
    private final EmployeeTeamScope teamScope;

    public EmployeeController(CreateEmployeeUseCase createEmployeeUseCase,
                               ListEmployeesUseCase listEmployeesUseCase,
                               ReassignStoreUseCase reassignStoreUseCase,
                               DeactivateEmployeeUseCase deactivateEmployeeUseCase,
                               ReactivateEmployeeUseCase reactivateEmployeeUseCase,
                               RegeneratePasswordUseCase regeneratePasswordUseCase,
                               UpdateEmployeeUseCase updateEmployeeUseCase,
                               ChangeEmployeeRoleUseCase changeEmployeeRoleUseCase,
                               SetEmployeePasswordUseCase setEmployeePasswordUseCase,
                               EmployeeRoleResolver employeeRoleResolver,
                               GetEmployeeActivityUseCase getEmployeeActivityUseCase,
                               EmployeeTeamScope teamScope) {
        this.createEmployeeUseCase = createEmployeeUseCase;
        this.listEmployeesUseCase = listEmployeesUseCase;
        this.reassignStoreUseCase = reassignStoreUseCase;
        this.deactivateEmployeeUseCase = deactivateEmployeeUseCase;
        this.reactivateEmployeeUseCase = reactivateEmployeeUseCase;
        this.regeneratePasswordUseCase = regeneratePasswordUseCase;
        this.updateEmployeeUseCase = updateEmployeeUseCase;
        this.changeEmployeeRoleUseCase = changeEmployeeRoleUseCase;
        this.setEmployeePasswordUseCase = setEmployeePasswordUseCase;
        this.employeeRoleResolver = employeeRoleResolver;
        this.getEmployeeActivityUseCase = getEmployeeActivityUseCase;
        this.teamScope = teamScope;
    }

    @Operation(summary = "Create a new employee",
            description = "Story 19.4 — phoneNumber and username are optional, at least one is required. A username is "
                    + "3 to 50 letters, digits, '.', '_' or '-', unique case-insensitively; the member signs in with it "
                    + "and the temporary password, returned once.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Employee created"),
            @ApiResponse(responseCode = "403", description = "Plan limit exceeded or not OWNER"),
            @ApiResponse(responseCode = "409",
                    description = "Phone already registered (USER_ALREADY_EXISTS) or username taken, case-insensitively "
                            + "(USERNAME_ALREADY_EXISTS)"),
            @ApiResponse(responseCode = "422",
                    description = "Neither phone nor username, or invalid username (VALIDATION_FAILED)")
    })
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<TempPasswordResponseDto>> createEmployee(
            @Valid @RequestBody CreateEmployeeRequestDto request) {
        teamScope.requireCreatable(request.storeId(), request.role());
        UUID actorId = CurrentActor.actorId();
        CreateEmployeeCommand command = new CreateEmployeeCommand(
                actorId, request.phoneNumber(), request.firstName(),
                request.lastName(), request.storeId(), request.username(), request.role());
        CreateEmployeeResult result = createEmployeeUseCase.execute(command);
        TempPasswordResponseDto dto = new TempPasswordResponseDto(
                toDto(result.employee()),
                result.temporaryPassword());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(dto));
    }

    @Operation(summary = "List all employees")
    @ApiResponse(responseCode = "200", description = "Employee list returned")
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<EmployeeResponseDto>>> listEmployees() {
        UUID scope = teamScope.listScope();
        UUID actorId = CurrentActor.actorId();
        List<Employee> employees = listEmployeesUseCase.execute(new ListEmployeesQuery(actorId, scope));
        Map<UUID, EmployeeRoleResolver.Enrichment> enrichment = enrich(employees);
        if (scope != null) { // MANAGER: his EMPLOYEE/VIEWER members and himself, filtered with the bulk enrichment
            employees = employees.stream()
                    .filter(e -> teamScope.isVisibleTeamMember(e.getUserId(), enrichment.get(e.getUserId())))
                    .toList();
        }
        List<EmployeeResponseDto> dtos = toDtos(employees, enrichment);
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Reassign employee to another store")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Store reassigned"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PatchMapping("/{employeeId}/store")
    public ResponseEntity<ApiResponseWrapper<EmployeeResponseDto>> reassignStore(
            @PathVariable UUID employeeId,
            @Valid @RequestBody ReassignStoreRequestDto request) {
        CurrentActor.requireOwner("OWNER role required");
        UUID actorId = CurrentActor.actorId();
        Employee updated = reassignStoreUseCase.execute(
                new ReassignStoreCommand(actorId, employeeId, request.storeId()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                toDto(updated)));
    }

    @Operation(summary = "Deactivate employee access")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Employee deactivated"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PatchMapping("/{employeeId}/deactivate")
    public ResponseEntity<ApiResponseWrapper<Void>> deactivateEmployee(
            @PathVariable UUID employeeId) {
        teamScope.requireManageable(employeeId);
        UUID actorId = CurrentActor.actorId();
        deactivateEmployeeUseCase.execute(new DeactivateEmployeeCommand(actorId, employeeId));
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Reactivate a previously deactivated employee")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Employee reactivated"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PatchMapping("/{employeeId}/reactivate")
    public ResponseEntity<ApiResponseWrapper<Void>> reactivateEmployee(
            @PathVariable UUID employeeId) {
        teamScope.requireManageable(employeeId);
        UUID actorId = CurrentActor.actorId();
        reactivateEmployeeUseCase.execute(new ReactivateEmployeeCommand(actorId, employeeId));
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Regenerate temporary password for an employee")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New temporary password generated"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PostMapping("/{employeeId}/regenerate-password")
    public ResponseEntity<ApiResponseWrapper<TempPasswordResponseDto>> regeneratePassword(
            @PathVariable UUID employeeId) {
        teamScope.requireManageable(employeeId);
        UUID actorId = CurrentActor.actorId();
        CreateEmployeeResult result = regeneratePasswordUseCase.execute(
                new RegeneratePasswordCommand(actorId, employeeId));
        TempPasswordResponseDto dto = new TempPasswordResponseDto(
                toDto(result.employee()),
                result.temporaryPassword());
        return ResponseEntity.ok(ApiResponseWrapper.ok(dto));
    }

    // ── Story 14.11 — new endpoints ──────────────────────────────────────

    @Operation(summary = "Update employee profile (name, phone, store)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated"),
            @ApiResponse(responseCode = "403", description = "Not an OWNER"),
            @ApiResponse(responseCode = "404", description = "Employee not found"),
            @ApiResponse(responseCode = "409", description = "Phone already registered by another user")
    })
    @PatchMapping("/{employeeId}")
    public ResponseEntity<ApiResponseWrapper<EmployeeResponseDto>> updateEmployee(
            @PathVariable UUID employeeId,
            @Valid @RequestBody UpdateEmployeeRequestDto request) {
        teamScope.requireManageable(employeeId);
        teamScope.requireNoStoreChange(request.storeId());
        if (!request.hasAtLeastOneField()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "At least one field must be provided for update");
        }
        UUID actorId = CurrentActor.actorId();
        Employee updated = updateEmployeeUseCase.execute(
                new UpdateEmployeeCommand(actorId, employeeId,
                        request.firstName(), request.lastName(),
                        request.phoneNumber(), request.storeId()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                toDto(updated)));
    }

    @Operation(summary = "Change a member's role (OWNER, MANAGER, EMPLOYEE, VIEWER) — OWNER only")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role changed"),
            @ApiResponse(responseCode = "403", description = "Not an OWNER, or anti-lockout guard"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PatchMapping("/{employeeId}/role")
    public ResponseEntity<ApiResponseWrapper<Void>> changeRole(
            @PathVariable UUID employeeId,
            @Valid @RequestBody ChangeRoleRequestDto request) {
        CurrentActor.requireOwner("OWNER role required");
        UUID actorId = CurrentActor.actorId();
        changeEmployeeRoleUseCase.execute(
                new ChangeEmployeeRoleCommand(actorId, employeeId, request.role()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Set a new password for an employee")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password set"),
            @ApiResponse(responseCode = "403", description = "Not an OWNER"),
            @ApiResponse(responseCode = "404", description = "Employee not found"),
            @ApiResponse(responseCode = "422", description = "Weak password")
    })
    @PostMapping("/{employeeId}/password")
    public ResponseEntity<ApiResponseWrapper<Void>> setPassword(
            @PathVariable UUID employeeId,
            @Valid @RequestBody SetPasswordRequestDto request) {
        teamScope.requireManageable(employeeId);
        UUID actorId = CurrentActor.actorId();
        setEmployeePasswordUseCase.execute(
                new SetEmployeePasswordCommand(actorId, employeeId, request.newPassword()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    // ── v1s-14-6 — FR67 employee activity (OWNER only) ───────────────────

    @Operation(summary = "Activity of every employee (last login, sales today/this week, session)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activity list returned"),
            @ApiResponse(responseCode = "403", description = "Not an OWNER")
    })
    @GetMapping("/activity")
    public ResponseEntity<ApiResponseWrapper<List<EmployeeActivityResponseDto>>> listActivity() {
        UUID scope = teamScope.listScope();
        List<EmployeeActivity> activity = scope == null ? getEmployeeActivityUseCase.forAll()
                : getEmployeeActivityUseCase.forStore(scope);
        if (scope != null && !activity.isEmpty()) { // MANAGER: same visibility as his team list, one bulk call
            Map<UUID, EmployeeRoleResolver.Enrichment> enrichment = employeeRoleResolver.resolveAll(activity.stream()
                    .map(EmployeeActivity::userId).collect(Collectors.toCollection(LinkedHashSet::new)));
            activity = activity.stream()
                    .filter(x -> teamScope.isVisibleTeamMember(x.userId(), enrichment.get(x.userId())))
                    .toList();
        }
        List<EmployeeActivityResponseDto> dtos = activity.stream()
                .map(EmployeeActivityResponseDto::fromDomain).toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Activity of one employee (last login, sales today/this week, session)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activity returned"),
            @ApiResponse(responseCode = "403", description = "Not an OWNER"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @GetMapping("/{employeeId}/activity")
    public ResponseEntity<ApiResponseWrapper<EmployeeActivityResponseDto>> getActivity(
            @PathVariable UUID employeeId) {
        teamScope.requireManageable(employeeId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                EmployeeActivityResponseDto.fromDomain(getEmployeeActivityUseCase.forEmployee(employeeId))));
    }

    /**
     * Enriches the aggregate with what lives outside it (membership role, {@code public.users} phone and username) —
     * Story 14.11 AC5/AC6, Story 19.4. Lists are enriched in bulk: three queries whatever the number of employees.
     */
    private EmployeeResponseDto toDto(Employee e) {
        return toDtos(List.of(e)).get(0);
    }

    private List<EmployeeResponseDto> toDtos(List<Employee> employees) {
        return toDtos(employees, enrich(employees));
    }

    private Map<UUID, EmployeeRoleResolver.Enrichment> enrich(List<Employee> employees) {
        if (employees.isEmpty()) {
            return Map.of();
        }
        return employeeRoleResolver.resolveAll(
                employees.stream().map(Employee::getUserId).collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private List<EmployeeResponseDto> toDtos(List<Employee> employees,
                                             Map<UUID, EmployeeRoleResolver.Enrichment> enrichment) {
        return employees.stream().map(e -> {
            EmployeeRoleResolver.Enrichment x = enrichment.get(e.getUserId());
            return EmployeeResponseDto.fromDomain(e, x.role(), x.phoneNumber(), x.username());
        }).toList();
    }
}
