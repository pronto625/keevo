package com.keevo.mcp.token.adapter.in.rest;

import com.keevo.mcp.module.McpAccessModule;
import com.keevo.mcp.server.McpServerConfig;
import com.keevo.mcp.token.application.service.McpAccessTokenService;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.RequiresEntitlement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Story 21.9 — « Accès IA externe »: the owner's personal access tokens of the MCP server ({@code POST /api/v1/mcp}).
 * Owner only — every OWNER of the company, co-owners included (21.9 reprise 2026-11-11), each managing their own
 * tokens only: another owner's token is never listed and its revocation answers 404 like an unknown id. Module
 * {@code mcp-access}: the tool list (READ) and the creation (WRITE); the token list and the
 * revocation stay open to the owner whatever the plan. The clear token is returned once, by the creation; the list
 * never carries it.
 */
@RestController
@RequestMapping("/api/v1/mcp")
@PreAuthorize("hasRole('OWNER')")
public class McpAccessTokenController {

    public record CreateRequest(
            @NotBlank(message = "Le nom de l'accès est obligatoire")
            @Size(max = McpAccessTokenService.MAX_NAME, message = "Le nom ne doit pas dépasser 60 caractères")
            String name,
            @NotEmpty(message = "Choisissez au moins une donnée accessible")
            @Size(max = McpAccessTokenService.MAX_TOOLS, message = "16 données au plus")
            List<String> tools,
            Integer expiresInDays) {
    }

    /** The creation answer: the token's view, the clear token (shown once) and the address to give the AI client. */
    public record CreatedResponse(McpAccessTokenService.TokenView accessToken, String token, String mcpUrl) {
    }

    private final McpAccessTokenService service;
    /** The public API address ({@code KEEVO_PUBLIC_BASE_URL}), as for the invoice links; blank = the request's. */
    private final String publicBaseUrl;

    public McpAccessTokenController(McpAccessTokenService service,
                                    @Value("${keevo.public-base-url:}") String publicBaseUrl) {
        this.service = service;
        String base = publicBaseUrl == null ? "" : publicBaseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.publicBaseUrl = base;
    }

    /** GET /api/v1/mcp/tools — what the owner may let an external AI client read (French labels). */
    @RequiresEntitlement(value = McpAccessModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/tools")
    public ResponseEntity<ApiResponseWrapper<List<McpAccessTokenService.ToolView>>> tools() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(service.selectableTools()));
    }

    /**
     * GET /api/v1/mcp/tokens — the owner's tokens, newest first (never the clear token). Not gated: an owner whose
     * plan lost the module still sees (and can revoke) the tokens it issued.
     */
    @EntitlementExempt("accès IA externes du propriétaire — consultables et révocables même sans le module")
    @GetMapping("/tokens")
    public ResponseEntity<ApiResponseWrapper<List<McpAccessTokenService.TokenView>>> list() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(service.list(TenantContext.getCurrentTenant(),
                CurrentActor.actorId())));
    }

    /**
     * POST /api/v1/mcp/tokens — {@code {name, tools[], expiresInDays?}} → 201 with the clear token (shown once);
     * 400 {@code VALIDATION_ERROR}; 409 {@code MCP_TOKEN_LIMIT} beyond 10 tokens.
     */
    @RequiresEntitlement(McpAccessModule.ID)
    @PostMapping("/tokens")
    public ResponseEntity<ApiResponseWrapper<CreatedResponse>> create(@Valid @RequestBody CreateRequest request) {
        McpAccessTokenService.Created created = service.create(TenantContext.getCurrentTenant(),
                CurrentActor.actorId(), request.name(), request.tools(), request.expiresInDays());
        String url = publicBaseUrl.isEmpty()
                ? ServletUriComponentsBuilder.fromCurrentContextPath().path(McpServerConfig.ENDPOINT).toUriString()
                : publicBaseUrl + McpServerConfig.ENDPOINT;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseWrapper.ok(new CreatedResponse(created.token(), created.secret(), url)));
    }

    /**
     * DELETE /api/v1/mcp/tokens/{id} — revoked at once → 204; 404 {@code MCP_TOKEN_NOT_FOUND}. Not gated: revoking
     * an access must always be possible (security), whatever the plan.
     */
    @EntitlementExempt("révocation d'un accès IA externe — toujours possible")
    @DeleteMapping("/tokens/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        service.revoke(TenantContext.getCurrentTenant(), CurrentActor.actorId(), id);
        return ResponseEntity.noContent().build();
    }
}
