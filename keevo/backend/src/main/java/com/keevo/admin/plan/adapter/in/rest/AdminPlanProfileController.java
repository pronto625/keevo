package com.keevo.admin.plan.adapter.in.rest;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.PlanProfileAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AdminPlanProfileController — super-admin plan profiles and limits (Story 16.8, MOD-FR13b).
 *
 * <p>ZERO business logic: parsing, then {@link PlanProfileAdmin}. {@link CurrentActor#requireSuperAdmin()} in every
 * method (second barrier behind the SecurityConfig {@code /api/v1/admin/**} rule). Errors: {@code PLAN_PROFILE_INVALID}
 * 422 with a French reason (modules named by display name), nothing saved; Story 16.14: {@code PLAN_PROFILE_NOT_FOUND}
 * 404 for an unknown plan type; Story 16.4: {@code ADMIN_JOB_RUNNING} 409
 * when the modules change while a re-sync job runs, nothing saved.
 */
@Tag(name = "Admin — Plan profiles", description = "Super Admin plan limits and the paid modules each plan grants")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/plan-profiles")
public class AdminPlanProfileController {

    private final PlanProfileAdmin profiles;

    public AdminPlanProfileController(PlanProfileAdmin profiles) {
        this.profiles = profiles;
    }

    @Operation(summary = "Plan profiles: limits (null = unlimited) and granted paid modules, per plan type",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<PlanProfileAdmin.ProfileView>>> list() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(profiles.list()));
    }

    @Operation(summary = "One plan profile (Story 16.14); 404 PLAN_PROFILE_NOT_FOUND for an unknown plan type",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{planType}")
    public ResponseEntity<ApiResponseWrapper<PlanProfileAdmin.ProfileView>> get(@PathVariable String planType) {
        CurrentActor.requireSuperAdmin();
        PlanType type = parsePlanType(planType);
        return ResponseEntity.ok(ApiResponseWrapper.ok(profiles.list().stream()
                .filter(p -> type.name().equals(p.planType()))
                .findFirst()
                .orElseThrow(() -> new DomainException(ErrorCode.PLAN_PROFILE_NOT_FOUND, "Offre introuvable"))));
    }

    @Operation(summary = "Replace the profile of a plan type (applies to the next check, no restart)",
            description = "Body keys maxStores / maxProducts / maxEmployees are required: positive, or null "
                    + "(unlimited) sent explicitly; unknown keys are refused. modules: ids of installed paid modules "
                    + "only (every other installed paid module is recorded as not granted). Tenants above a lowered limit keep their records; only new "
                    + "creations are refused. Answer {profile, compositionChanged, jobId}: when the modules change, "
                    + "the plan-derived module rows of every live tenant on this plan type are re-synced by a "
                    + "PROFILE_RESYNC background job (follow it with GET /api/v1/admin/jobs/{jobId}: progress, then "
                    + "the per-tenant report, tenants by code; owner notifications sent by the job); rows set by a "
                    + "super-admin are untouched; jobId is null when the modules did not change. 422 "
                    + "PLAN_PROFILE_INVALID, nothing saved; 409 ADMIN_JOB_RUNNING when the modules change while a "
                    + "re-sync is still running, nothing saved; 404 PLAN_PROFILE_NOT_FOUND for an unknown plan type.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/{planType}")
    public ResponseEntity<ApiResponseWrapper<PlanProfileAdmin.UpdateResult>> update(
            @PathVariable String planType,
            @RequestBody(required = false) Map<String, Object> body) {
        CurrentActor.requireSuperAdmin();
        PlanType type = parsePlanType(planType);
        if (body == null) {
            throw invalid("indiquez les plafonds et les modules de l'offre");
        }
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) {
                throw invalid("la demande contient un champ non reconnu");
            }
        }
        Integer maxStores = limit(body, "maxStores", "le plafond de boutiques");
        Integer maxProducts = limit(body, "maxProducts", "le plafond de produits");
        Integer maxEmployees = limit(body, "maxEmployees", "le plafond d'employés");
        return ResponseEntity.ok(ApiResponseWrapper.ok(profiles.update(type, maxStores, maxProducts, maxEmployees,
                modules(body), CurrentActor.actorId())));
    }

    // ── Parsing (adapter concern) ─────────────────────────────────────────────

    /** The accepted body keys: the three limits must be present (null = unlimited, sent explicitly). */
    private static final Set<String> FIELDS = Set.of("maxStores", "maxProducts", "maxEmployees", "modules");

    private static Integer limit(Map<String, Object> body, String key, String what) {
        if (!body.containsKey(key)) {
            throw invalid("indiquez " + what + " (vide pour illimité)");
        }
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) <= Integer.MAX_VALUE) {
            return n.intValue();
        }
        throw invalid(what + " doit être un nombre entier (vide pour illimité)");
    }

    private static Set<String> modules(Map<String, Object> body) {
        Object value = body.get("modules");
        if (value == null) {
            return null; // refused by the service with a French reason
        }
        if (!(value instanceof List<?> list)) {
            throw invalid("la liste des modules est invalide");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object id : list) {
            if (!(id instanceof String s)) {
                throw invalid("un des modules demandés n'existe pas");
            }
            ids.add(s);
        }
        return ids;
    }

    private static PlanType parsePlanType(String value) {
        try {
            return PlanType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            // Story 16.14 — an unknown plan type is a missing resource, like an unknown pack or job
            throw new DomainException(ErrorCode.PLAN_PROFILE_NOT_FOUND, "Offre introuvable");
        }
    }

    private static DomainException invalid(String reason) {
        return new DomainException(ErrorCode.PLAN_PROFILE_INVALID, reason, Map.of("reason", reason));
    }
}
