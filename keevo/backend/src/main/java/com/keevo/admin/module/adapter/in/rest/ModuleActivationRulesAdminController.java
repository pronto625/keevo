package com.keevo.admin.module.adapter.in.rest;

import com.keevo.admin.module.adapter.in.rest.dto.ModuleActivationRuleSetDto;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.ModuleActivationRulesAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ModuleActivationRulesAdminController — super-admin initial module activation rules (Story 19.13, ADR-05): by sale
 * flow and optional sector, the modules disabled at start for a NEW tenant (onboarding, Bref import). Existing
 * tenants are never touched.
 *
 * <p>ZERO business logic: parsing, then {@link ModuleActivationRulesAdmin}. {@link CurrentActor#requireSuperAdmin()} in
 * every method (second barrier behind the SecurityConfig {@code /api/v1/admin/**} rule). Errors:
 * {@code MODULE_ACTIVATION_RULE_INVALID} 422 with a French reason (nothing saved), {@code INVALID_STATE} 409 (a default
 * set cannot be deleted), {@code NOT_FOUND} 404.
 */
@Tag(name = "Admin — Module activation rules",
        description = "Super Admin: modules disabled at start for a new tenant, by sale flow and sector")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/module-activation-rules")
public class ModuleActivationRulesAdminController {

    /** The body keys of a rule set: all required ({@code sectorType} may be null). */
    private static final Set<String> FIELDS = Set.of("saleFlow", "sectorType", "disabledModules");

    private final ModuleActivationRulesAdmin rules;

    public ModuleActivationRulesAdminController(ModuleActivationRulesAdmin rules) {
        this.rules = rules;
    }

    @Operation(summary = "Every rule set: sale flow, sector (null = default), modules disabled at start",
            description = "Ordered by sale flow (POS, then DOCUMENT), then sector (default set first). For a new "
                    + "tenant the most specific set wins with no merge: the (flow, sector) set if it exists, else "
                    + "the flow's default set.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<ModuleActivationRuleSetDto>>> list() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                rules.list().stream().map(ModuleActivationRuleSetDto::from).toList()));
    }

    @Operation(summary = "Create or replace the rule set of a sale flow and sector",
            description = "Body {saleFlow: POS|DOCUMENT, sectorType: sector code or null, disabledModules: [ids]} — "
                    + "all keys required, unknown keys refused. disabledModules: installed modules the owner may "
                    + "toggle, no duplicates, dependency-closed (a module required by another must not be disabled "
                    + "alone); empty = nothing disabled. The most specific set wins with no merge: a sector set "
                    + "replaces the flow's default set for that sector. 201 when created, 200 when replaced. 422 "
                    + "MODULE_ACTIVATION_RULE_INVALID, nothing saved. Existing tenants are not changed.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping
    public ResponseEntity<ApiResponseWrapper<ModuleActivationRuleSetDto>> save(
            @RequestBody(required = false) Map<String, Object> body) {
        CurrentActor.requireSuperAdmin();
        if (body == null) {
            throw invalid("indiquez le parcours de vente, le secteur et les modules désactivés");
        }
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) {
                throw invalid("la demande contient un champ non reconnu");
            }
        }
        for (String key : FIELDS) {
            if (!body.containsKey(key)) {
                throw invalid(switch (key) {
                    case "saleFlow" -> "indiquez le parcours de vente";
                    case "sectorType" -> "indiquez le secteur (vide pour la règle par défaut)";
                    default -> "indiquez les modules désactivés au départ (liste vide si aucun)";
                });
            }
        }
        ModuleActivationRulesAdmin.SaveResult result = rules.save(text(body.get("saleFlow"), "le parcours de vente"),
                text(body.get("sectorType"), "le secteur"), modules(body.get("disabledModules")),
                CurrentActor.actorId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponseWrapper.ok(ModuleActivationRuleSetDto.from(result.ruleSet())));
    }

    @Operation(summary = "Delete the rule set of a sector (the default set of a sale flow cannot be deleted)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam(required = false) String saleFlow,
                                       @RequestParam(required = false) String sectorType) {
        CurrentActor.requireSuperAdmin();
        rules.delete(saleFlow, sectorType, CurrentActor.actorId());
        return ResponseEntity.noContent().build();
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
            ids.add(s);
        }
        return ids;
    }

    private static DomainException invalid(String reason) {
        return DomainException.userFacing(ErrorCode.MODULE_ACTIVATION_RULE_INVALID, "Règle non enregistrée : " + reason,
                Map.of("reason", reason));
    }
}
