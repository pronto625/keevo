package com.keevo.catalog.product.adapter.in.mcp;

import com.keevo.shared.ai.AiToolArguments;
import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.catalog.api.ProductState;
import com.keevo.catalog.api.ProductView;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Story 21.8 — AI tools of the {@code catalog} module, mirroring {@code ProductController#listProducts} (every member
 * role, module {@code catalog} READ, the non-archived products of the company — searched and bounded in SQL,
 * {@link ProductQueryPort#searchActive}). The purchase price is
 * given to the owner only (stricter than the REST list, which predates the AI). No product id.
 */
@Component
public class ProductMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int MAX_SEARCH_LENGTH = 100;

    private final ProductQueryPort products;

    public ProductMcpTools(ProductQueryPort products) {
        this.products = products;
    }

    public record ProductRow(String name, String reference, int price, Integer purchasePrice, Integer stock,
                             boolean stockTracked, int alertThreshold, String status) {
    }

    /** {@code truncated}: more products match than returned (narrow the search). */
    public record ProductsResult(int returned, boolean truncated, List<ProductRow> products) {
    }

    @Tool(name = "search_products", description = "Recherche des produits du catalogue (non archivés, brouillons compris) par nom, référence ou "
            + "code-barres (ou liste par ordre alphabétique sans recherche) : prix de vente, stock total, suivi du "
            + "stock, seuil d'alerte, prix d'achat (propriétaire seulement). Prix en FCFA.")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = "catalog", access = RequiresEntitlement.Access.READ)
    public ProductsResult searchProducts(
            @ToolParam(required = false, description = "Partie du nom, de la référence ou du code-barres")
            String search,
            @ToolParam(required = false, description = "Nombre de produits, 1 à 50 (défaut 20)") Integer limit) {
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        String text = AiToolArguments.search(search);
        boolean owner = CurrentActor.isOwner();
        // one bounded query (limit + 1 rows: the extra one only tells whether more products match)
        List<ProductView> found = products.searchActive(TenantContext.getCurrentTenant(), text, max + 1);
        List<ProductRow> rows = found.stream().limit(max)
                .map(p -> new ProductRow(p.name(), p.sku() != null ? p.sku() : p.barcode(), p.price(),
                        owner ? p.buyPrice() : null, p.stockQuantity(), p.trackStock(), p.minimumThreshold(),
                        p.status() == ProductState.DRAFT ? "Brouillon" : "Actif"))
                .toList();
        return new ProductsResult(rows.size(), found.size() > max, rows);
    }

}
