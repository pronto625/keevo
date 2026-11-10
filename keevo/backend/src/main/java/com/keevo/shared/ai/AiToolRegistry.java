package com.keevo.shared.ai;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Story 21.8 — the AI tool layer (FUS-FR39, decision D8): read-only tools defined once on the Keevo use cases, as
 * {@code @Tool} methods of one {@code {Module}McpTools} bean per module ({@code adapter/in/mcp/}). Consumed by the
 * in-app assistant (21.4) and the external MCP server (21.9).
 *
 * <p>The session is the calling thread's: the authenticated actor ({@code SecurityContextHolder}, as the JWT filter
 * sets it) and the tenant ({@code TenantContext}). Tool arguments never carry a tenant, an actor or a role. A call goes
 * through the bean's Spring proxy, so the role ({@code @PreAuthorize}), permission ({@code @RequiresPermission}) and
 * module ({@code @RequiresEntitlement}, READ) gates are the very interceptors of the REST API, and the store / employee
 * scope is computed by the same code as the REST handler. Every call is audited ({@code public.ai_tool_calls}: tool,
 * channel, actor, outcome, duration — never arguments or results).
 */
public interface AiToolRegistry {

    /** Every tool of the application, sorted by name (documentation, coverage test). */
    List<AiToolDescriptor> allTools();

    /**
     * The tools the current session may call now: role rule, permissions and module state checked; empty without an
     * authenticated actor or a tenant. Sorted by name.
     */
    List<AiToolDescriptor> allowedTools();

    /**
     * {@link #allowedTools()} as Spring AI callbacks (for a chat client or an MCP server); each callback calls
     * {@link #call} with {@code channel} and never throws: a refusal or an error becomes a JSON object
     * {@code {"erreur": "<message en français>"}} for the model.
     */
    List<ToolCallback> allowedToolCallbacks(AiToolChannel channel);

    /**
     * Calls one tool by name with its JSON arguments ({@code null} or blank = no argument) for the current session.
     * Never throws; audited whatever the outcome (when a tenant is bound).
     */
    AiToolResult call(String toolName, String jsonArguments, AiToolChannel channel);
}
