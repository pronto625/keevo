package com.keevo.mcp.server;

import com.keevo.mcp.token.application.service.McpToolLabels;
import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.ai.AiToolResult;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ErrorMessages;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Story 21.9 — Keevo's MCP request handler (stateless Streamable HTTP; the SDK transport parses JSON-RPC and calls it
 * on the HTTP request thread, where {@code CredentialAuthFilter} bound the token owner's session).
 *
 * <p>Command dispatch on the JSON-RPC method: {@code initialize} (server info, tools capability, French instructions),
 * {@code ping}, {@code tools/list} (the token's tools still allowed to its owner now: {@link AiToolRegistry#allowedTools()}
 * ∩ the token scope), {@code tools/call} (scope check → AI quota check → {@link AiToolRegistry#call} on channel
 * {@code MCP}, which applies the REST gates and audits → one {@code AI_CREDITS} counted for an OK call only); any other
 * method → JSON-RPC error {@code -32601}. Tool failures are tool results with {@code isError} and the French message
 * (what MCP clients show their model); nothing here throws (the transport would echo an exception message).
 *
 * <p>Cost: {@code tools/list} = O(tools) in memory after one module read; {@code tools/call} = 1 rolled-back quota
 * transaction + the tool's bounded use case + 1 audit insert + 1 quota transaction.
 */
@Component
public class KeevoMcpServerHandler implements McpStatelessServerHandler {

    private static final Logger log = LoggerFactory.getLogger(KeevoMcpServerHandler.class);

    /** Streamable HTTP protocol versions this server speaks; another requested version gets the latest. */
    static final List<String> PROTOCOL_VERSIONS = List.of(ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);

    static final String SERVER_NAME = "keevo";
    static final String SERVER_TITLE = "Keevo";
    static final String SERVER_VERSION = "1.0.0";

    static final String INSTRUCTIONS = "Serveur Keevo : les données de gestion de l'entreprise du propriétaire "
            + "(ventes, factures, clients, produits, stock, dépenses, résultats), en lecture seule. Montants en FCFA, "
            + "dates au format AAAA-MM-JJ, fuseau Afrique/Douala. L'identifiant storeId sert uniquement à filtrer sur "
            + "une boutique : ne l'affichez jamais. Chaque appel d'outil réussi compte un crédit IA de l'offre.";

    static final String OUT_OF_SCOPE = "Cet outil n'est pas autorisé pour cet accès. Le propriétaire peut créer un "
            + "accès qui l'inclut dans l'application Keevo.";
    static final String UNKNOWN_METHOD = "Méthode non prise en charge par le serveur Keevo";
    static final String INVALID_PARAMS = "Paramètres de la requête invalides";
    static final String INTERNAL = "Une erreur est survenue. Réessayez dans un instant.";

    private final AiToolRegistry tools;
    private final UsageQuotaPort quota;
    private final TransactionTemplate tx;
    private final McpJsonMapper json;

    public KeevoMcpServerHandler(AiToolRegistry tools, UsageQuotaPort quota, TransactionTemplate tx,
                                 McpJsonMapper keevoMcpJsonMapper) {
        this.tools = tools;
        this.quota = quota;
        this.tx = tx;
        this.json = keevoMcpJsonMapper;
    }

    @Override
    public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext context,
                                                          McpSchema.JSONRPCRequest request) {
        // evaluated on subscription, i.e. on the HTTP request thread that blocks on it (the bound session's thread)
        return Mono.fromSupplier(() -> dispatch(context, request));
    }

    @Override
    public Mono<Void> handleNotification(McpTransportContext context, McpSchema.JSONRPCNotification notification) {
        return Mono.empty(); // notifications/initialized and the like: nothing to do on a stateless server
    }

    McpSchema.JSONRPCResponse dispatch(McpTransportContext context, McpSchema.JSONRPCRequest request) {
        try {
            Object session = context == null ? null : context.get(McpRequestSession.ATTRIBUTE);
            if (!(session instanceof McpRequestSession s) || TenantContext.getCurrentTenant() == null) {
                return error(request, McpSchema.ErrorCodes.INTERNAL_ERROR, INTERNAL);
            }
            return switch (request.method()) {
                case McpSchema.METHOD_INITIALIZE -> ok(request, initialize(request.params()));
                case McpSchema.METHOD_PING -> ok(request, Map.of());
                case McpSchema.METHOD_TOOLS_LIST -> ok(request, new McpSchema.ListToolsResult(listTools(s), null));
                case McpSchema.METHOD_TOOLS_CALL -> callTool(request, s);
                default -> error(request, McpSchema.ErrorCodes.METHOD_NOT_FOUND, UNKNOWN_METHOD);
            };
        } catch (RuntimeException e) {
            log.warn("MCP request {} failed ({})", request.method(), e.getClass().getSimpleName());
            return error(request, McpSchema.ErrorCodes.INTERNAL_ERROR, INTERNAL);
        }
    }

    private McpSchema.InitializeResult initialize(Object params) {
        String requested = null;
        if (params != null) {
            try {
                McpSchema.InitializeRequest init = json.convertValue(params,
                        new TypeRef<McpSchema.InitializeRequest>() {
                        });
                requested = init == null ? null : init.protocolVersion();
            } catch (IllegalArgumentException e) {
                // malformed parameters: answer with the latest version
            }
        }
        String version = requested != null && PROTOCOL_VERSIONS.contains(requested) ? requested
                : PROTOCOL_VERSIONS.get(PROTOCOL_VERSIONS.size() - 1);
        McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder().tools(false).build();
        return new McpSchema.InitializeResult(version, capabilities,
                new McpSchema.Implementation(SERVER_NAME, SERVER_TITLE, SERVER_VERSION), INSTRUCTIONS);
    }

    /** The token's tools the owner may still call now, sorted by name. */
    List<McpSchema.Tool> listTools(McpRequestSession session) {
        McpSchema.ToolAnnotations readOnly = new McpSchema.ToolAnnotations(null, true, false, true, false, null);
        return tools.allowedTools().stream()
                .filter(d -> session.tools().contains(d.name()))
                .map(d -> McpSchema.Tool.builder()
                        .name(d.name())
                        .title(McpToolLabels.label(d))
                        .description(d.description())
                        .inputSchema(json, d.inputSchema())
                        .annotations(readOnly)
                        .build())
                .toList();
    }

    private McpSchema.JSONRPCResponse callTool(McpSchema.JSONRPCRequest request, McpRequestSession session) {
        McpSchema.CallToolRequest call;
        if (request.params() == null) {
            return error(request, McpSchema.ErrorCodes.INVALID_PARAMS, INVALID_PARAMS);
        }
        try {
            call = json.convertValue(request.params(), new TypeRef<McpSchema.CallToolRequest>() {
            });
        } catch (IllegalArgumentException e) {
            return error(request, McpSchema.ErrorCodes.INVALID_PARAMS, INVALID_PARAMS);
        }
        if (call == null || call.name() == null || call.name().isBlank()) {
            return error(request, McpSchema.ErrorCodes.INVALID_PARAMS, INVALID_PARAMS);
        }
        if (!session.tools().contains(call.name())) {
            return ok(request, toolError(OUT_OF_SCOPE));
        }
        String schema = TenantContext.getCurrentTenant();
        try {
            checkQuota(schema);
        } catch (DomainException e) {
            return ok(request, toolError(message(e)));
        }
        String arguments;
        try {
            arguments = call.arguments() == null ? null : json.writeValueAsString(call.arguments());
        } catch (java.io.IOException e) {
            return error(request, McpSchema.ErrorCodes.INVALID_PARAMS, INVALID_PARAMS);
        }
        AiToolResult result = tools.call(call.name(), arguments, AiToolChannel.MCP);
        if (!result.isOk()) {
            return ok(request, toolError(result.message() != null ? result.message() : INTERNAL));
        }
        consume(schema);
        return ok(request, McpSchema.CallToolResult.builder().addTextContent(result.json()).isError(false).build());
    }

    /** The 19.8 increment in a rolled-back transaction: the exact refusal ({@code QUOTA_EXCEEDED}) or nothing kept. */
    private void checkQuota(String schema) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            quota.assertAndIncrementForSchema(schema, UsageMetric.AI_CREDITS, 1);
        });
    }

    /**
     * One AI credit for a served call. The tool already ran: a failure here (credits used up meanwhile by another
     * consumer, counter unavailable) never turns the served result into an error — served uncounted, logged.
     */
    private void consume(String schema) {
        try {
            tx.executeWithoutResult(status -> quota.assertAndIncrementForSchema(schema, UsageMetric.AI_CREDITS, 1));
        } catch (DomainException e) {
            if (e.getErrorCode() == ErrorCode.QUOTA_EXCEEDED) {
                log.warn("[MCP] AI credits used up during a tool call: result served without counting");
            } else {
                log.warn("[MCP] AI credit not counted ({}): result served", e.getDomainCode());
            }
        } catch (RuntimeException e) {
            log.warn("[MCP] AI credit not counted ({}): result served", e.getClass().getSimpleName());
        }
    }

    private static String message(DomainException e) {
        if (e.getUserMessage() != null && !e.getUserMessage().isBlank()) {
            return e.getUserMessage();
        }
        return ErrorMessages.of(e.getDomainCode(), INTERNAL);
    }

    private static McpSchema.CallToolResult toolError(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static McpSchema.JSONRPCResponse ok(McpSchema.JSONRPCRequest request, Object result) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null);
    }

    private static McpSchema.JSONRPCResponse error(McpSchema.JSONRPCRequest request, int code, String message) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
                new McpSchema.JSONRPCResponse.JSONRPCError(code, message, null));
    }
}
