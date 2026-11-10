package com.keevo.shared.ai;

/**
 * Story 21.8 — who calls an AI tool, recorded in the call audit ({@code public.ai_tool_calls.channel}).
 */
public enum AiToolChannel {
    /** The in-app assistant (Story 21.4): guided topics and free-text questions. */
    ASSISTANT,
    /** An external MCP client authenticated by a personal token (Story 21.9). */
    MCP,
    /** Platform code and tests. */
    INTERNAL
}
