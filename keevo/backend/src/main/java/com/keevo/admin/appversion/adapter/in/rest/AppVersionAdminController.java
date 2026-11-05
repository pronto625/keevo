package com.keevo.admin.appversion.adapter.in.rest;

import com.keevo.admin.appversion.adapter.in.rest.dto.AppVersionPolicyDto;
import com.keevo.identity.api.AppVersionAdminPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AppVersionAdminController — super-admin minimum app versions (Story 19.10, FUS-FR31): per app identity and
 * platform, the minimum version (below it the app must update), the latest version, the store link and the message.
 *
 * <p>ZERO business logic: body parsing, then {@link AppVersionAdminPort}. {@link CurrentActor#requireSuperAdmin()}
 * in every method (second barrier behind the SecurityConfig {@code /api/v1/admin/**} rule). Errors:
 * {@code APP_VERSION_INVALID} 422 with a French reason (nothing saved), {@code APP_VERSION_NOT_FOUND} 404.
 */
@Tag(name = "Admin — App versions", description = "Super Admin minimum and latest app versions per platform")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/app-versions")
public class AppVersionAdminController {

    /** The body keys: all required, each may be null. */
    private static final Set<String> FIELDS = Set.of("minVersion", "latestVersion", "storeUrl", "message");

    private final AppVersionAdminPort versions;

    public AppVersionAdminController(AppVersionAdminPort versions) {
        this.versions = versions;
    }

    @Operation(summary = "Recorded app versions: identity, platform, minimum, latest, store link, message",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<AppVersionPolicyDto>>> list() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(versions.list().stream().map(AppVersionPolicyDto::from)
                .toList()));
    }

    @Operation(summary = "Create or replace the versions of an app on a platform",
            description = "identity: kebab-case, 30 characters at most (bref, keevo). platform: ios | android | web. "
                    + "Body keys minVersion, latestVersion, storeUrl and message are all required, each may be null "
                    + "(blank = null); unknown keys are refused. Versions MAJOR[.MINOR[.PATCH]] (20 characters at "
                    + "most, no -pre / +build suffix), minVersion not above latestVersion; storeUrl https://, 500 "
                    + "characters at most; message "
                    + "500 characters at most. 201 when created, 200 when replaced. 422 APP_VERSION_INVALID, nothing "
                    + "saved. Audited.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/{identity}/{platform}")
    public ResponseEntity<ApiResponseWrapper<AppVersionPolicyDto>> save(
            @PathVariable String identity,
            @PathVariable String platform,
            @RequestBody(required = false) Map<String, Object> body) {
        CurrentActor.requireSuperAdmin();
        if (body == null) {
            throw invalid("indiquez la version minimale, la dernière version, le lien du store et le message");
        }
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) {
                throw invalid("la demande contient un champ non reconnu");
            }
        }
        for (String key : FIELDS) {
            if (!body.containsKey(key)) {
                throw invalid(switch (key) {
                    case "minVersion" -> "indiquez la version minimale (null si aucune)";
                    case "latestVersion" -> "indiquez la dernière version (null si aucune)";
                    case "storeUrl" -> "indiquez le lien du store (null si aucun)";
                    default -> "indiquez le message (null si aucun)";
                });
            }
        }
        AppVersionAdminPort.SaveResult result = versions.save(identity, platform,
                text(body.get("minVersion"), "la version minimale"),
                text(body.get("latestVersion"), "la dernière version"),
                text(body.get("storeUrl"), "le lien du store"),
                text(body.get("message"), "le message"),
                CurrentActor.actorId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponseWrapper.ok(AppVersionPolicyDto.from(result.policy())));
    }

    @Operation(summary = "Delete the versions of an app on a platform (no update required nor offered any more)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/{identity}/{platform}")
    public ResponseEntity<Void> delete(@PathVariable String identity, @PathVariable String platform) {
        CurrentActor.requireSuperAdmin();
        versions.delete(identity, platform, CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }

    // ── Parsing (adapter concern) ─────────────────────────────────────────────

    private static String text(Object value, String what) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw invalid(what + " doit être un texte");
    }

    private static DomainException invalid(String reason) {
        String sentence = Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
        return DomainException.userFacing(ErrorCode.APP_VERSION_INVALID, sentence, Map.of("reason", reason));
    }
}
