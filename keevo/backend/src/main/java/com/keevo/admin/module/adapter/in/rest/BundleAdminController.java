package com.keevo.admin.module.adapter.in.rest;

import com.keevo.admin.module.adapter.in.rest.dto.BundleActivationDto;
import com.keevo.admin.module.adapter.in.rest.dto.BundleDto;
import com.keevo.admin.module.adapter.in.rest.dto.ModuleActivationRequest;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.BundleAdmin;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleLifecycle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BundleAdminController — super-admin bundles (Story 16.2, MOD-FR12): named sets of modules applied to a tenant in
 * one activation plan through the à-la-carte engine.
 *
 * <p>ZERO business logic: parsing, then {@link BundleAdmin}. {@link CurrentActor#requireSuperAdmin()} in every method
 * (second barrier behind the SecurityConfig {@code /api/v1/admin/**} rule). Errors: {@code BUNDLE_NOT_FOUND} 404
 * « Pack introuvable », {@code BUNDLE_INVALID} 422 with a French reason (modules named by display name, nothing
 * saved), and on apply the module activation errors ({@code TENANT_NOT_FOUND} 404, {@code VALIDATION_ERROR} 422 —
 * no row written for any module —, {@code MODULE_PROVISIONING_FAILED} 500…).
 */
@Tag(name = "Admin — Bundles", description = "Super Admin bundles: named sets of modules applied in one activation")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin")
public class BundleAdminController {

    /** The body keys of a bundle definition: all required ({@code description} may be null or empty). */
    private static final Set<String> FIELDS = Set.of("name", "description", "modules");

    private final BundleAdmin bundles;

    public BundleAdminController(BundleAdmin bundles) {
        this.bundles = bundles;
    }

    @Operation(summary = "Bundles: id, name, description, modules with their display names",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/bundles")
    public ResponseEntity<ApiResponseWrapper<List<BundleDto>>> list() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(bundles.list().stream().map(BundleDto::from).toList()));
    }

    @Operation(summary = "Create or replace a bundle",
            description = "bundleId: kebab-case (pack-pro). Body keys name, description and modules are required "
                    + "(description may be null or empty); unknown keys are refused. modules: ids of installed "
                    + "modules, neither core nor platform, at least one, no duplicates. 201 when created, 200 when "
                    + "replaced. 422 BUNDLE_INVALID, nothing saved. Tenants that already applied the bundle are "
                    + "not changed.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/bundles/{bundleId}")
    public ResponseEntity<ApiResponseWrapper<BundleDto>> save(
            @PathVariable String bundleId,
            @RequestBody(required = false) Map<String, Object> body) {
        CurrentActor.requireSuperAdmin();
        if (body == null) {
            throw invalid("indiquez le nom, la description et les modules du pack");
        }
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) {
                throw invalid("la demande contient un champ non reconnu");
            }
        }
        for (String key : FIELDS) {
            if (!body.containsKey(key)) {
                throw invalid(switch (key) {
                    case "name" -> "indiquez le nom du pack";
                    case "description" -> "indiquez la description du pack (vide si aucune)";
                    default -> "indiquez les modules du pack";
                });
            }
        }
        BundleAdmin.SaveResult result = bundles.save(bundleId, text(body.get("name"), "le nom du pack"),
                text(body.get("description"), "la description du pack"), modules(body.get("modules")),
                CurrentActor.actorId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponseWrapper.ok(BundleDto.from(result.bundle())));
    }

    @Operation(summary = "Delete a bundle (definition only: tenants that applied it keep their modules)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/bundles/{bundleId}")
    public ResponseEntity<Void> delete(@PathVariable String bundleId) {
        CurrentActor.requireSuperAdmin();
        bundles.delete(bundleId, CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Apply a bundle to a tenant: one activation of all its modules (dependencies first)",
            description = "Body optional, same as a module activation: status ACTIVE (default) or TRIAL, expiresAt "
                    + "(ISO instant or date, future) for the paid modules; TRIAL without a date = 14 days for each "
                    + "paid module not enabled. Free modules are activated without end. All or nothing: a refused "
                    + "validation writes no row. Returns the modules activated and those already active.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/tenants/{tenantId}/bundles/{bundleId}")
    public ResponseEntity<ApiResponseWrapper<BundleActivationDto>> apply(
            @PathVariable UUID tenantId,
            @PathVariable String bundleId,
            @RequestBody(required = false) ModuleActivationRequest request) {
        CurrentActor.requireSuperAdmin();
        ModuleLifecycle.ActivationRequest activation = new ModuleLifecycle.ActivationRequest(
                ModuleAdminController.parseStatus(request != null ? request.status() : null),
                ModuleAdminController.parseExpiry(request != null ? request.expiresAt() : null));
        return ResponseEntity.ok(ApiResponseWrapper.ok(BundleActivationDto.from(
                bundles.apply(tenantId, bundleId, activation, ModuleAdminController.actor()))));
    }

    // ── Parsing (adapter concern) ─────────────────────────────────────────────

    private static String text(Object value, String what) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw invalid(what + " doit être un texte");
    }

    private static List<String> modules(Object value) {
        if (value == null) {
            return null; // refused by the service with a French reason
        }
        if (!(value instanceof List<?> list)) {
            throw invalid("la liste des modules est invalide");
        }
        List<String> ids = new ArrayList<>();
        for (Object id : list) {
            if (!(id instanceof String s)) {
                throw invalid("un des modules demandés n'existe pas");
            }
            ids.add(s); // duplicates kept: refused by the service, naming the module
        }
        return ids;
    }

    private static DomainException invalid(String reason) {
        String sentence = Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
        return new DomainException(ErrorCode.BUNDLE_INVALID, sentence, Map.of("reason", reason));
    }
}
