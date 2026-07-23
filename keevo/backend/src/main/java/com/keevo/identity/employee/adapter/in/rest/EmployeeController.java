package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.employee.adapter.in.rest.dto.*;
import com.keevo.identity.employee.domain.model.CreateEmployeeResult;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * EmployeeController — REST adapter for employee management (Story 3.5).
 *
 * <p>All endpoints require JWT authentication (OWNER only).
 */
@Tag(name = "Employees", description = "Employee management")
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

    public EmployeeController(CreateEmployeeUseCase createEmployeeUseCase,
                               ListEmployeesUseCase listEmployeesUseCase,
                               ReassignStoreUseCase reassignStoreUseCase,
                               DeactivateEmployeeUseCase deactivateEmployeeUseCase,
                               ReactivateEmployeeUseCase reactivateEmployeeUseCase,
                               RegeneratePasswordUseCase regeneratePasswordUseCase,
                               UpdateEmployeeUseCase updateEmployeeUseCase,
                               ChangeEmployeeRoleUseCase changeEmployeeRoleUseCase,
                               SetEmployeePasswordUseCase setEmployeePasswordUseCase,
                               EmployeeRoleResolver employeeRoleResolver) {
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
    }

    @Operation(summary = "Create a new employee")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Employee created"),
            @ApiResponse(responseCode = "403", description = "Plan limit exceeded or not OWNER"),
            @ApiResponse(responseCode = "409", description = "Phone already registered")
    })
    @PostMapping
    public ResponseEntity<ApiResponseWrapper<TempPasswordResponseDto>> createEmployee(
            @Valid @RequestBody CreateEmployeeRequestDto request) {
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
        CreateEmployeeCommand command = new CreateEmployeeCommand(
                actorId, request.phoneNumber(), request.firstName(),
                request.lastName(), request.storeId());
        CreateEmployeeResult result = createEmployeeUseCase.execute(command);
        TempPasswordResponseDto dto = new TempPasswordResponseDto(
                EmployeeResponseDto.fromDomain(result.employee(),
                        employeeRoleResolver.resolveRole(result.employee().getUserId())),
                result.temporaryPassword());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(dto));
    }

    @Operation(summary = "List all employees")
    @ApiResponse(responseCode = "200", description = "Employee list returned")
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<EmployeeResponseDto>>> listEmployees() {
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
        List<EmployeeResponseDto> dtos = listEmployeesUseCase
                .execute(new ListEmployeesQuery(actorId))
                .stream().map(e -> EmployeeResponseDto.fromDomain(e,
                        employeeRoleResolver.resolveRole(e.getUserId()))).toList();
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
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
        Employee updated = reassignStoreUseCase.execute(
                new ReassignStoreCommand(actorId, employeeId, request.storeId()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                EmployeeResponseDto.fromDomain(updated,
                        employeeRoleResolver.resolveRole(updated.getUserId()))));
    }

    @Operation(summary = "Deactivate employee access")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Employee deactivated"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PatchMapping("/{employeeId}/deactivate")
    public ResponseEntity<ApiResponseWrapper<Void>> deactivateEmployee(
            @PathVariable UUID employeeId) {
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
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
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
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
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
        CreateEmployeeResult result = regeneratePasswordUseCase.execute(
                new RegeneratePasswordCommand(actorId, employeeId));
        TempPasswordResponseDto dto = new TempPasswordResponseDto(
                EmployeeResponseDto.fromDomain(result.employee(),
                        employeeRoleResolver.resolveRole(result.employee().getUserId())),
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
        requireOwnerOrForbid();
        if (!request.hasAtLeastOneField()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "At least one field must be provided for update");
        }
        UUID actorId = extractActorId();
        Employee updated = updateEmployeeUseCase.execute(
                new UpdateEmployeeCommand(actorId, employeeId,
                        request.firstName(), request.lastName(),
                        request.phoneNumber(), request.storeId()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                EmployeeResponseDto.fromDomain(updated,
                        employeeRoleResolver.resolveRole(updated.getUserId()))));
    }

    @Operation(summary = "Change employee role (OWNER ↔ EMPLOYEE)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role changed"),
            @ApiResponse(responseCode = "403", description = "Not an OWNER, or anti-lockout guard"),
            @ApiResponse(responseCode = "404", description = "Employee not found")
    })
    @PatchMapping("/{employeeId}/role")
    public ResponseEntity<ApiResponseWrapper<Void>> changeRole(
            @PathVariable UUID employeeId,
            @Valid @RequestBody ChangeRoleRequestDto request) {
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
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
        requireOwnerOrForbid();
        UUID actorId = extractActorId();
        setEmployeePasswordUseCase.execute(
                new SetEmployeePasswordCommand(actorId, employeeId, request.newPassword()));
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    /**
     * Throws FORBIDDEN if the caller is not an OWNER.
     * Controller-level RBAC: all EmployeeController endpoints are OWNER-only (AC5).
     */
    private void requireOwnerOrForbid() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "ROLE_OWNER".equals(a.getAuthority()))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "OWNER role required");
        }
    }

    private UUID extractActorId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
