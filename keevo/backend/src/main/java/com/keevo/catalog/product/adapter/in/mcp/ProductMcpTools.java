package com.keevo.catalog.product.adapter.in.mcp;

import com.keevo.shared.ai.AiToolArguments;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductStockQuantities;
import com.keevo.catalog.api.ProductView;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.security.PurchasePriceVisibility;
import org.springframework.beans.factory.ObjectProvider;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code catalog} module, mirroring {@code ProductController#listProducts} (every member
 * role, module {@code catalog} READ, the non-archived products of the company — searched and bounded in SQL,
 * {@link ProductQueryPort#searchActive}). No product id.
 *
 * <p>Story 16.13 (reprise 10/11/2026): the purchase price follows the single policy {@link PurchasePriceVisibility}
 * (OWNER and MANAGER — same rule as the REST product list); the stock is the real one ({@code stock_levels}, through
 * {@link ProductStockQuantities}, one grouped query) — every store for the owner, the member's own store only (none
 * without a store, none for a product without stock tracking) — never the legacy {@code products.stock_quantity}.
 */
@Component
public class ProductMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int MAX_SEARCH_LENGTH = 100;

    private final ProductQueryPort products;
    private final ObjectProvider<ProductStockQuantities> stock; // optional — the catalogue starts without stock
    private final PurchasePriceVisibility purchasePrices;

    public ProductMcpTools(ProductQueryPort products, ObjectProvider<ProductStockQuantities> stock,
                           PurchasePriceVisibility purchasePrices) {
        this.products = products;
        this.stock = stock;
        this.purchasePrices = purchasePrices;
    }

    public record ProductRow(String name, String reference, int price, Integer purchasePrice, Integer stock,
                             boolean stockTracked, int alertThreshold, String status) {
    }

    /** {@code truncated}: more products match than returned (narrow the search). */
    public record ProductsResult(int returned, boolean truncated, List<ProductRow> products) {
    }

    @Tool(name = "search_products", description = "Recherche des produits du catalogue (non archivés, brouillons compris) par nom, référence ou "
            + "code-barres (ou liste par ordre alphabétique sans recherche) : prix de vente, stock (toutes les "
            + "boutiques pour le propriétaire, sa boutique pour un employé), suivi du stock, seuil d'alerte, prix "
            + "d'achat (propriétaire et gérant seulement). Prix en FCFA.")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    public ProductsResult searchProducts(
            @ToolParam(required = false, description = "Partie du nom, de la référence ou du code-barres")
            String search,
            @ToolParam(required = false, description = "Nombre de produits, 1 à 50 (défaut 20)") Integer limit) {
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        String text = AiToolArguments.search(search);
        boolean purchasePriceVisible = purchasePrices.currentActorCanView();
        // one bounded query (limit + 1 rows: the extra one only tells whether more products match)
        List<ProductView> found = products.searchActive(TenantContext.getCurrentTenant(), text, max + 1);
        List<ProductView> page = found.stream().limit(max).toList();
        Map<UUID, Integer> quantities = quantities(page);
        List<ProductRow> rows = page.stream()
                .map(p -> new ProductRow(p.name(), p.sku() != null ? p.sku() : p.barcode(), p.price(),
                        purchasePriceVisible ? p.buyPrice() : null,
                        quantities == null || !p.trackStock() ? null : quantities.getOrDefault(p.id(), 0),
                        p.trackStock(), p.minimumThreshold(),
                        p.status() == ProductState.DRAFT ? "Brouillon" : "Actif"))
                .toList();
        return new ProductsResult(rows.size(), found.size() > max, rows);
    }

    /**
     * Story 16.13 (reprise, D1) — the stock the actor may read for these products, one grouped query: every store for
     * the owner, the member's own store; {@code null} (no stock shown) for a member without a store, for any other
     * actor, or when the inventory module is absent.
     */
    private Map<UUID, Integer> quantities(List<ProductView> page) {
        ProductStockQuantities port = stock.getIfAvailable();
        if (port == null || page.isEmpty()) {
            return page.isEmpty() ? Map.of() : null;
        }
        UUID onlyStore;
        if (CurrentActor.isOwner()) {
            onlyStore = null;
        } else if (CurrentActor.isStoreMember() && CurrentActor.assignedStoreIdOrNull() != null) {
            onlyStore = CurrentActor.assignedStoreIdOrNull();
        } else {
            return null;
        }
        List<UUID> tracked = page.stream().filter(ProductView::trackStock).map(ProductView::id).toList();
        return port.quantities(TenantContext.getCurrentTenant(), tracked, onlyStore);
    }

}
