package com.keevo.identity.role.adapter.in.rest;

import com.keevo.identity.role.application.service.CustomRoleService;
import com.keevo.identity.role.domain.model.CustomRole;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.CustomRoleGrant;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.application.ModulePermissions;
import com.keevo.shared.module.application.PermissionCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 19.14 — the roles of a company: the owner creates roles (« Caissier »…), ticks their rights among the
 * permissions of his usable modules (French labels, never a code on screen), restricts them to stores; every member
 * reads his own effective rights ({@code GET /roles/me}) so the app shows only what he may do. The built-in roles
 * (« Gérant », « Employé », « Lecteur ») stay the defaults and are listed as presets.
 */
@Tag(name = "Roles", description = "Owner-defined roles and permissions")
@EntitlementExempt("core: rôles de l'équipe")
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    /** One permission with its French label. */
    public record PermissionDto(String code, String label) {
    }

    /**
     * A custom role.
     *
     * @param storeIds    the stores it applies to, {@code null} = every store
     * @param memberCount members holding it
     */
    public record RoleDto(UUID id, String name, String baseRole, String baseRoleLabel, List<PermissionDto> permissions,
                          List<UUID> storeIds, int memberCount) {
    }

    /** A built-in role shown as a preset. */
    public record BuiltInRoleDto(String baseRole, String name, List<PermissionDto> permissions) {
    }

    /** The roles screen: presets, then the company's roles. */
    public record RolesDto(List<BuiltInRoleDto> builtIn, List<RoleDto> custom) {
    }

    /**
     * Create / update body.
     *
     * @param baseRole    {@code MANAGER} | {@code EMPLOYEE} | {@code VIEWER} (required on creation, immutable after)
     * @param permissions permission codes from {@code GET /roles/permissions}
     * @param storeIds    {@code null} or absent = every store; otherwise a non-empty list of the company's stores
     */
    public record RoleRequest(String name, String baseRole, List<String> permissions, List<UUID> storeIds) {
    }

    /** The current member's rights. */
    public record MyRightsDto(CustomRoleDto customRole, List<String> permissions) {
    }

    /** The current member's custom role. */
    public record CustomRoleDto(UUID id, String name) {
    }

    private final CustomRoleService roles;
    private final PermissionCatalog catalog;
    private final ModulePermissions permissions;

    public RoleController(CustomRoleService roles, PermissionCatalog catalog, ModulePermissions permissions) {
        this.roles = roles;
        this.catalog = catalog;
        this.permissions = permissions;
    }

    @Operation(summary = "Permissions an owner may give a role",
            description = "The permissions of the company's usable modules, grouped by module, with French labels.")
    @GetMapping("/permissions")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<List<PermissionCatalog.Group>>> catalogue() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(catalog.grantable(TenantContext.getCurrentTenant())));
    }

    @Operation(summary = "Roles of the company", description = "Built-in presets and the owner-defined roles.")
    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<RolesDto>> list() {
        List<BuiltInRoleDto> builtIn = roles.builtIns().stream()
                .map(b -> new BuiltInRoleDto(b.baseRole(), b.name(), labelled(b.permissions()))).toList();
        List<RoleDto> custom = roles.list(TenantContext.getCurrentTenant()).stream()
                .map(r -> toDto(r.role(), r.memberCount())).toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(new RolesDto(builtIn, custom)));
    }

    @Operation(summary = "Create a role")
    @ApiResponse(responseCode = "201", description = "Role created")
    @ApiResponse(responseCode = "409", description = "Another role has this name (ROLE_NAME_TAKEN)")
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<RoleDto>> create(@RequestBody RoleRequest request) {
        CustomRole role = roles.create(TenantContext.getCurrentTenant(), CurrentActor.actorId(), input(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(toDto(role, 0)));
    }

    @Operation(summary = "Update a role", description = "Name, permissions and stores; the base role never changes.")
    @PutMapping("/{roleId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<RoleDto>> update(@PathVariable UUID roleId,
                                                              @RequestBody RoleRequest request) {
        CustomRole.WithMembers updated = roles.update(TenantContext.getCurrentTenant(), CurrentActor.actorId(), roleId,
                input(request));
        return ResponseEntity.ok(ApiResponseWrapper.ok(toDto(updated.role(), updated.memberCount())));
    }

    @Operation(summary = "Delete a role", description = "Refused while members hold it (ROLE_IN_USE).")
    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponseWrapper<Void>> delete(@PathVariable UUID roleId) {
        roles.delete(TenantContext.getCurrentTenant(), CurrentActor.actorId(), roleId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "My rights",
            description = "The current member's custom role (null when none) and effective permission codes.")
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    public ResponseEntity<ApiResponseWrapper<MyRightsDto>> me() {
        Authentication auth = CurrentActor.authentication();
        CustomRoleGrant grant = auth != null && auth.getDetails() instanceof CustomRoleGrant.Carrier carrier
                ? carrier.customRole() : null;
        List<String> held = permissions.permissionsOf(auth).stream().sorted().toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(new MyRightsDto(
                grant == null ? null : new CustomRoleDto(grant.roleId(), grant.roleName()), held)));
    }

    private static CustomRoleService.RoleInput input(RoleRequest request) {
        if (request == null) {
            return new CustomRoleService.RoleInput(null, null, null, null);
        }
        return new CustomRoleService.RoleInput(request.name(), request.baseRole(), request.permissions(),
                request.storeIds());
    }

    private static RoleDto toDto(CustomRole role, int members) {
        return new RoleDto(role.id(), role.name(), role.baseRole(),
                CustomRoleService.BASE_ROLE_LABELS.get(role.baseRole()), labelled(role.permissions()),
                role.storeIds(), members);
    }

    /** Labelled permissions sorted by label; a permission without label (unknown) is never returned. */
    private static List<PermissionDto> labelled(Collection<String> codes) {
        return codes.stream()
                .map(code -> new PermissionDto(code, PermissionCatalog.label(code)))
                .filter(p -> Objects.nonNull(p.label()))
                .sorted(java.util.Comparator.comparing(PermissionDto::label))
                .toList();
    }
}
