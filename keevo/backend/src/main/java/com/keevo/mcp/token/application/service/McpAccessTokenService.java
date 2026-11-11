package com.keevo.mcp.token.application.service;

import com.keevo.mcp.token.domain.model.McpAccessToken;
import com.keevo.mcp.token.domain.model.McpTokenSecrets;
import com.keevo.mcp.token.domain.port.out.McpAccessTokenRepository;
import com.keevo.shared.ai.AiToolDescriptor;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.auth.McpAccessTokenChangedEvent;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story 21.9 — the owner's personal access tokens of the MCP server: create (shown once), list, revoke.
 *
 * <p>Reprise 2026-11-11 — « owner » is any OWNER membership of the company (co-owners included, 19.6): every method is
 * keyed by (company, person), so a co-owner never sees nor revokes another owner's token, and the cap is per person.
 *
 * <p>A token never grants more than its owner: its tools are chosen among the tools the owner's session may call now
 * ({@link AiToolRegistry#allowedTools()}), and every MCP request intersects them again with the owner's live rights.
 * Bounds: {@value #MAX_TOKENS} tokens per owner and company, name 1–{@value #MAX_NAME} characters, 1–{@value #MAX_TOOLS}
 * tools, lifetime 7, 30, 90 (default) or 365 days. Creation and revocation are audited (name only).
 *
 * <p>Tokens cut by the owner's revocation cutoff ({@code tokens_valid_after}) are deleted when the owner lists or
 * creates (refused anyway; they must neither look active nor use the cap).
 *
 * <p>Cost: list = 1 cutoff DELETE (owner index) + 1 indexed query (≤ {@value #MAX_TOKENS} rows); create = the owner's tool listing (in memory after one
 * module read) + 1 advisory lock + 1 count + 1 insert in one transaction; revoke = 1 DELETE … RETURNING by primary key and owner.
 */
@Service
public class McpAccessTokenService {

    public static final int MAX_TOKENS = 10;
    public static final int MAX_NAME = 60;
    public static final int MAX_TOOLS = 16;
    public static final int DEFAULT_DAYS = 90;
    public static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90, 365);

    /** A token as the API returns it (labels in French, never the hash). */
    public record TokenView(UUID id, String name, String hint, List<ToolView> tools, Instant createdAt,
                            Instant expiresAt, Instant lastUsedAt, boolean expired) {
    }

    /**
     * A tool as the owner sees it: its name (the handle the API takes), its French label, and whether the owner may
     * still use it now (a tool lost with a plan or module change stays in the access but is neither listed nor served
     * to the AI client).
     */
    public record ToolView(String name, String label, boolean available) {
    }

    /** The creation result: the clear token, returned this once only. */
    public record Created(TokenView token, String secret) {
    }

    private final McpAccessTokenRepository tokens;
    private final AiToolRegistry tools;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public McpAccessTokenService(McpAccessTokenRepository tokens, AiToolRegistry tools,
                                 ApplicationEventPublisher events, JdbcTemplate jdbc, Clock clock) {
        this.tokens = tokens;
        this.tools = tools;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The tools the owner may put in a token now (their own tools), with French labels. */
    public List<ToolView> selectableTools() {
        return tools.allowedTools().stream().map(d -> new ToolView(d.name(), McpToolLabels.label(d), true)).toList();
    }

    /**
     * The owner's tokens in the company, newest first — read-only: tokens cut by a session-wide revocation are left
     * out by the query (deleted at the next creation), 21.4.2 review.
     */
    public List<TokenView> list(String schema, UUID ownerId) {
        Map<String, AiToolDescriptor> catalogue = catalogue();
        Set<String> allowed = allowedNames();
        Instant now = clock.instant();
        return tokens.list(schema, ownerId).stream().map(t -> view(t, catalogue, allowed, now)).toList();
    }

    /**
     * Creates a token for the owner.
     *
     * @throws InvalidRequestParameterException name, tools or lifetime invalid (French message)
     * @throws DomainException                  {@code MCP_TOKEN_LIMIT} beyond {@value #MAX_TOKENS} tokens
     */
    @Transactional
    public Created create(String schema, UUID ownerId, String name, List<String> toolNames, Integer days) {
        String label = name == null ? "" : name.strip();
        if (label.isEmpty() || label.length() > MAX_NAME) {
            throw new InvalidRequestParameterException("name", "name length",
                    "Le nom de l'accès est obligatoire (60 caractères au plus)");
        }
        int lifetime = days == null ? DEFAULT_DAYS : days;
        if (!ALLOWED_DAYS.contains(lifetime)) {
            throw new InvalidRequestParameterException("expiresInDays", "lifetime",
                    "La durée de validité doit être de 7, 30, 90 ou 365 jours");
        }
        Set<String> scope = scope(toolNames);

        // serialise the cap check per owner and company (two concurrent creations cannot both pass it)
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "mcp-token:" + schema + ":" + ownerId);
        tokens.deleteCutOff(schema, ownerId);
        // expired tokens wait 30 days for the purge: they never hold a place under the cap (21.4.2 review)
        if (tokens.countActive(schema, ownerId, clock.instant()) >= MAX_TOKENS) {
            throw new DomainException(ErrorCode.MCP_TOKEN_LIMIT);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String secret = McpTokenSecrets.generate();
        McpAccessToken token = new McpAccessToken(UUID.randomUUID(), label, McpTokenSecrets.hint(secret),
                List.copyOf(scope), now, now.plus(Duration.ofDays(lifetime)), null);
        if (!tokens.insert(schema, ownerId, token, McpTokenSecrets.hash(secret))) {
            throw new IllegalStateException("MCP token insert: unknown company schema");
        }
        events.publishEvent(new McpAccessTokenChangedEvent(ownerId, schema, token.id(), token.name(),
                McpAccessTokenChangedEvent.Change.CREATED, now));
        return new Created(view(token, catalogue(), scope, now), secret);
    }

    /**
     * Revokes the owner's token: deleted, refused at the next MCP request on every instance.
     *
     * @throws DomainException {@code MCP_TOKEN_NOT_FOUND} when unknown or another user's
     */
    @Transactional
    public void revoke(String schema, UUID ownerId, UUID tokenId) {
        String name = tokens.delete(schema, ownerId, tokenId)
                .orElseThrow(() -> new DomainException(ErrorCode.MCP_TOKEN_NOT_FOUND));
        events.publishEvent(new McpAccessTokenChangedEvent(ownerId, schema, tokenId, name,
                McpAccessTokenChangedEvent.Change.REVOKED, clock.instant()));
    }

    /** The requested tools, checked against the owner's tools now; order kept, duplicates dropped. */
    private Set<String> scope(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            throw new InvalidRequestParameterException("tools", "empty",
                    "Choisissez au moins une donnée accessible");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String n : toolNames) {
            if (n != null && !n.isBlank()) {
                requested.add(n.strip());
            }
        }
        if (requested.isEmpty() || requested.size() > MAX_TOOLS) {
            throw new InvalidRequestParameterException("tools", "count",
                    "Choisissez entre 1 et " + MAX_TOOLS + " données accessibles");
        }
        if (!allowedNames().containsAll(requested)) {
            throw new InvalidRequestParameterException("tools", "not allowed",
                    "Une des données choisies n'est pas disponible pour votre compte");
        }
        return requested;
    }

    private Set<String> allowedNames() {
        return tools.allowedTools().stream().map(AiToolDescriptor::name).collect(Collectors.toSet());
    }

    private Map<String, AiToolDescriptor> catalogue() {
        return tools.allTools().stream().collect(Collectors.toMap(AiToolDescriptor::name, d -> d, (a, b) -> a));
    }

    private static TokenView view(McpAccessToken t, Map<String, AiToolDescriptor> catalogue, Set<String> allowed,
                                  Instant now) {
        // a tool the catalogue no longer knows stays visible, unavailable (the owner sees the access lost it)
        List<ToolView> scope = t.tools().stream().map(name -> {
            AiToolDescriptor d = catalogue.get(name);
            return d == null ? new ToolView(name, McpToolLabels.UNKNOWN_LABEL, false)
                    : new ToolView(d.name(), McpToolLabels.label(d), allowed.contains(d.name()));
        }).toList();
        return new TokenView(t.id(), t.name(), t.hint(), scope, t.createdAt(), t.expiresAt(), t.lastUsedAt(),
                t.isExpired(now));
    }
}
