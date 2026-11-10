package com.keevo.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;

/**
 * Story 21.9 — the MCP server endpoint, {@code POST /api/v1/mcp} (Streamable HTTP, stateless: every request carries
 * its personal access token; {@code GET} answers 405, no SSE stream, no server session to share between instances).
 *
 * <p>Decision: Spring AI 1.1.8's MCP server starter auto-configures one server-wide tool list built at startup; Keevo
 * needs, per request, the tools of the token still allowed to its owner, and keeps auto-configuration out of the app.
 * So the SDK transport that starter uses ({@link WebMvcStatelessServerTransport}, MCP Java SDK 0.18.3) is wired here by
 * hand, with Keevo's handler ({@link KeevoMcpServerHandler}). The transport is a {@link RouterFunction} served by the
 * DispatcherServlet behind the security chain, where {@code CredentialAuthFilter} + {@link McpTokenAuthenticator}
 * bound the token owner's session; the transport context carries the token's scope ({@link McpRequestSession}).
 * The JSON mapper is a plain Jackson one (the MCP records carry their own annotations; the app's mapper settings must
 * not change the protocol).
 */
@Configuration
public class McpServerConfig {

    public static final String ENDPOINT = "/api/v1/mcp";

    @Bean
    public McpJsonMapper keevoMcpJsonMapper() {
        return new JacksonMcpJsonMapper(new ObjectMapper());
    }

    @Bean
    public WebMvcStatelessServerTransport keevoMcpTransport(McpJsonMapper keevoMcpJsonMapper,
                                                            KeevoMcpServerHandler handler) {
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(keevoMcpJsonMapper)
                .messageEndpoint(ENDPOINT)
                .contextExtractor(request -> request.attribute(McpRequestSession.ATTRIBUTE)
                        .map(session -> McpTransportContext.create(Map.of(McpRequestSession.ATTRIBUTE, session)))
                        .orElse(McpTransportContext.EMPTY))
                .build();
        transport.setMcpHandler(handler);
        return transport;
    }

    @Bean
    public RouterFunction<ServerResponse> keevoMcpRoutes(WebMvcStatelessServerTransport keevoMcpTransport) {
        return keevoMcpTransport.getRouterFunction();
    }
}
