package com.keevo.crm.contact.application.usecase;

import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.crm.contact.domain.service.ClientVisibility;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * GetClientsUseCase — retrieve the client list, optionally searching by name/phone (Story 2.5).
 *
 * <p>Story 20.2 (ADR-08) — limited to what the actor sees: 1 stores query (visible set) + 1 clients query.
 */
@Service
public class GetClientsUseCase {

    private final ClientRepository clientRepository;
    private final ClientStoreScope storeScope;

    public GetClientsUseCase(ClientRepository clientRepository, ClientStoreScope storeScope) {
        this.clientRepository = clientRepository;
        this.storeScope = storeScope;
    }

    /**
     * @param ownerStoreFilter OWNER only — narrows to what is visible from that store; ignored for a member
     */
    public record GetClientsQuery(String searchQuery, boolean includeArchived, ClientAccess access,
                                  UUID ownerStoreFilter, String tenantId) {
        /** Pre-20.2 arity — unrestricted. */
        public GetClientsQuery(String searchQuery, boolean includeArchived) {
            this(searchQuery, includeArchived, ClientAccess.SYSTEM, null, null);
        }
    }

    public List<Client> execute(GetClientsQuery query) {
        ClientVisibility.Scope scope = scope(query);
        if (query.searchQuery() != null && !query.searchQuery().isBlank()) {
            return clientRepository.searchByNameOrPhone(query.searchQuery().trim(), scope);
        }
        return clientRepository.findAll(query.includeArchived(), scope);
    }

    // ── Story 20.6 — keyset pages ─────────────────────────────────────────────────────────────────────────

    /** Largest page ({@code limit} 1–100). */
    public static final int MAX_LIMIT = 100;

    /**
     * One page of the list in {@code (lower(name), id)} order: {@code limit + 1} rows read (one indexed query), the
     * extra one only telling whether a next page exists. Same search, archive and visibility rules as
     * {@link #execute}.
     *
     * @param after {@code null} = first page
     */
    public ClientPage executePage(GetClientsQuery query, int limit, ClientCursor after) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit out of range");
        }
        ClientVisibility.Scope scope = scope(query);
        List<Client> rows = clientRepository.findPage(query.searchQuery(), query.includeArchived(), scope,
                after == null ? null : new ClientRepository.ClientKeyset(after.name(), after.id()), limit + 1);
        if (rows.size() <= limit) {
            return new ClientPage(rows, null);
        }
        List<Client> page = rows.subList(0, limit);
        Client last = page.get(limit - 1);
        return new ClientPage(List.copyOf(page), new ClientCursor(last.name(), last.id()));
    }

    private ClientVisibility.Scope scope(GetClientsQuery query) {
        return storeScope.readScope(query.access(),
                query.access().owner() ? query.ownerStoreFilter() : null, query.tenantId());
    }

    /** A page and the position after its last row ({@code null} on the last page). */
    public record ClientPage(List<Client> items, ClientCursor next) {
    }

    /**
     * The opaque {@code cursor} of the client list: base64url of {@code <id>:<name>} of the last row returned (the
     * database compares {@code lower(name)} itself, so the order and the cursor always agree).
     */
    public record ClientCursor(String name, UUID id) {

        public String encode() {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (id + ":" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        /** @throws IllegalArgumentException not a cursor of this list */
        public static ClientCursor decode(String token) {
            if (token == null || token.isBlank() || token.length() > 2000) {
                throw new IllegalArgumentException("invalid cursor");
            }
            String raw = new String(java.util.Base64.getUrlDecoder().decode(token.strip()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (raw.length() < 38 || raw.charAt(36) != ':') {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new ClientCursor(raw.substring(37), UUID.fromString(raw.substring(0, 36)));
        }
    }
}
