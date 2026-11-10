package com.keevo.crm.contact.adapter.in.mcp;

import com.keevo.crm.contact.adapter.in.CurrentClientAccess;
import com.keevo.crm.contact.application.service.ClientStoreScope;
import com.keevo.crm.contact.application.usecase.GetClientsUseCase;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.crm.contact.domain.entity.ClientType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code crm} module, mirroring {@code ClientController#listClients} (every member role,
 * module {@code crm} READ) with its keyset page (Story 20.6) and store visibility (Story 20.2,
 * {@link CurrentClientAccess}: the owner sees every client or one store's, a member the clients visible from their
 * store, any store filter ignored). No client id; the page cursor is opaque.
 */
@Component
public class ClientMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final String INVALID_CURSOR = "Curseur de pagination invalide";

    private final GetClientsUseCase clients;

    public ClientMcpTools(GetClientsUseCase clients) {
        this.clients = clients;
    }

    public record ClientRow(String name, String phone, String email, String type, boolean archived) {
    }

    public record ClientsPage(List<ClientRow> clients, String nextCursor) {
    }

    @Tool(name = "search_clients", description = "Recherche des clients par nom ou téléphone (ou liste par ordre "
            + "alphabétique sans recherche) : nom, téléphone, e-mail, type (particulier ou entreprise). Par pages : "
            + "passer nextCursor pour la suite. Le propriétaire voit tous les clients (ou ceux d'une boutique), un "
            + "employé ceux de sa boutique.")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
    public ClientsPage searchClients(
            @ToolParam(required = false, description = "Partie du nom ou du téléphone") String search,
            @ToolParam(required = false, description = "true = inclure les clients archivés") Boolean includeArchived,
            @ToolParam(required = false, description = "Identifiant d'une boutique (outil list_stores), "
                    + "propriétaire seulement") UUID storeId,
            @ToolParam(required = false, description = "Clients par page, 1 à 50 (défaut 20)") Integer limit,
            @ToolParam(required = false, description = "nextCursor de la page précédente") String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        GetClientsUseCase.ClientCursor after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = GetClientsUseCase.ClientCursor.decode(cursor);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestParameterException("cursor", "cursor is malformed", INVALID_CURSOR);
            }
        }
        var query = new GetClientsUseCase.GetClientsQuery(search, Boolean.TRUE.equals(includeArchived),
                CurrentClientAccess.get(), storeId, TenantContext.getCurrentTenant());
        GetClientsUseCase.ClientPage page;
        try {
            page = clients.executePage(query, size, after);
        } catch (DomainException e) {
            if (ClientStoreScope.isUnknownStore(e)) { // same 400 as the REST list
                String message = e.getUserMessage() != null ? e.getUserMessage() : e.getMessage();
                throw new InvalidRequestParameterException(ClientStoreScope.STORE_FIELD, message, message);
            }
            throw e;
        }
        return new ClientsPage(page.items().stream().map(ClientMcpTools::row).toList(),
                page.next() == null ? null : page.next().encode());
    }

    private static ClientRow row(Client c) {
        ClientType type = c.profile() == null ? null : c.profile().type();
        return new ClientRow(c.name(), c.phone(), c.email(),
                type == ClientType.COMPANY ? "Entreprise" : "Particulier", c.archived());
    }
}
