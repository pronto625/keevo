package com.keevo.store.store.adapter.in.mcp;

import com.keevo.store.api.StoreType;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.in.ListStoresQuery;
import com.keevo.store.store.domain.port.in.ListStoresUseCase;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code store} module, mirroring {@code StoreController#listStores} (OWNER only, module
 * {@code store} READ). The store id is the only id returned: the key the other tools take as {@code storeId}.
 */
@Component
public class StoreMcpTools {

    private final ListStoresUseCase listStores;

    public StoreMcpTools(ListStoresUseCase listStores) {
        this.listStores = listStores;
    }

    /** One store of the company. {@code storeId} is a key for the other tools' store filter, never to be displayed. */
    public record StoreItem(UUID storeId, String name, String kind, String address, boolean active) {
    }

    @Tool(name = "list_stores", description = "Liste les boutiques et l'entrepôt de l'entreprise (nom, type, adresse). "
            + "Donne l'identifiant storeId à passer aux autres outils pour filtrer sur une boutique ; "
            + "ne jamais afficher cet identifiant à l'utilisateur. Réservé au propriétaire.")
    @PreAuthorize("hasRole('OWNER')")
    @RequiresEntitlement(value = "store", access = RequiresEntitlement.Access.READ)
    public List<StoreItem> listStores() {
        return listStores.execute(new ListStoresQuery(false)).stream()
                .sorted(Comparator.comparing(Store::name, String.CASE_INSENSITIVE_ORDER))
                .map(s -> new StoreItem(s.id(), s.name(), s.type() == StoreType.WAREHOUSE ? "Entrepôt" : "Boutique",
                        s.address(), s.isActive()))
                .toList();
    }
}
