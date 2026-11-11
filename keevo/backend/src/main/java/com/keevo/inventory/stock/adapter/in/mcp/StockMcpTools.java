package com.keevo.inventory.stock.adapter.in.mcp;

import com.keevo.shared.ai.AiToolArguments;
import com.keevo.inventory.stock.adapter.in.StockReadScopes;
import com.keevo.inventory.stock.domain.model.StockReadScope;
import com.keevo.inventory.stock.domain.model.StoreProductStockEntry;
import com.keevo.inventory.stock.domain.model.StoreStockSummary;
import com.keevo.inventory.stock.domain.port.in.GetMultiStoreOverviewQuery;
import com.keevo.inventory.stock.domain.port.in.GetMultiStoreOverviewUseCase;
import com.keevo.inventory.stock.domain.port.in.GetStoreStockDetailQuery;
import com.keevo.inventory.stock.domain.port.in.GetStoreStockDetailUseCase;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.store.api.StoreType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Story 21.8 — AI tools of the {@code inventory} module, mirroring {@code MultiStoreStockController} (every member
 * role, module {@code multi-store} READ) and their store scope (Story 16.13 reprise, {@code StockReadScopes}): a store
 * member (MANAGER, EMPLOYEE, VIEWER) only gets their assigned store, any other {@code storeId} is refused, nothing
 * without store; the owner every store. The store id is returned by the overview as the
 * key of {@code low_stock_products}; no product or variant id.
 */
@Component
public class StockMcpTools {

    static final int DEFAULT_SIZE = 25;
    static final int MAX_SIZE = 50;
    static final String OTHER_STORE_MESSAGE = StockReadScope.OTHER_STORE_MESSAGE;

    private final GetMultiStoreOverviewUseCase overview;
    private final GetStoreStockDetailUseCase detail;

    public StockMcpTools(GetMultiStoreOverviewUseCase overview, GetStoreStockDetailUseCase detail) {
        this.overview = overview;
        this.detail = detail;
    }

    public record StoreStock(UUID storeId, String store, String kind, int products, long stockValue,
                             int lowStockProducts) {
    }

    @Tool(name = "stock_by_store", description = "Stock de chaque boutique et de l'entrepôt (un employé : sa boutique seulement) : nombre de produits en "
            + "stock, valeur du stock au prix de vente et nombre de produits en stock bas. Donne storeId pour "
            + "low_stock_products (ne pas l'afficher). Valeurs en FCFA.")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = "multi-store", access = RequiresEntitlement.Access.READ)
    public List<StoreStock> stockByStore() {
        // Story 16.13 (reprise): the scope is applied in SQL (one store for a member), no in-memory filtering
        return overview.execute(new GetMultiStoreOverviewQuery(StockReadScopes.current())).stream()
                .map(StockMcpTools::store).toList();
    }

    private static StoreStock store(StoreStockSummary s) {
        return new StoreStock(s.storeId(), s.storeName(), s.storeType() == StoreType.WAREHOUSE ? "Entrepôt" : "Boutique",
                s.productCount(), s.totalValueXaf(), s.lowStockCount());
    }

    public record ProductStock(String product, String variant, int quantity, int alertThreshold, String level) {
    }

    public record StoreStockPage(long totalProducts, int page, int size, List<ProductStock> products) {
    }

    @Tool(name = "low_stock_products", description = "Produits d'une boutique avec leur quantité et leur seuil "
            + "d'alerte, les plus critiques d'abord (rupture, puis stock bas), par pages. lowOnly = true (défaut) "
            + "pour ne garder que les produits en rupture ou en stock bas. Par défaut la boutique de l'employé ; le "
            + "propriétaire indique storeId (outil stock_by_store ou list_stores).")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
    @RequiresEntitlement(value = "multi-store", access = RequiresEntitlement.Access.READ)
    public StoreStockPage lowStockProducts(
            @ToolParam(required = false, description = "Identifiant de la boutique ; par défaut celle de l'employé")
            UUID storeId,
            @ToolParam(required = false, description = "true (défaut) = seulement rupture et stock bas")
            Boolean lowOnly,
            @ToolParam(required = false, description = "Numéro de page, à partir de 0") Integer page,
            @ToolParam(required = false, description = "Produits par page, 1 à 50 (défaut 25)") Integer size) {
        StockReadScope scope = StockReadScopes.current();
        UUID store = scope.requireReadable(storeId); // member: own store by default, another one → 403
        if (store == null) {
            throw new InvalidRequestParameterException("storeId", "store required",
                    "Précisez la boutique (outil stock_by_store).");
        }
        int pageNumber = AiToolArguments.page(page);
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        Page<StoreProductStockEntry> result = detail.execute(new GetStoreStockDetailQuery(store, pageNumber, pageSize,
                true, lowOnly == null || lowOnly, scope));
        return new StoreStockPage(result.getTotalElements(), pageNumber, pageSize,
                result.getContent().stream().map(StockMcpTools::product).toList());
    }

    private static ProductStock product(StoreProductStockEntry e) {
        String level = e.isCritical() ? "Rupture" : e.isLow() ? "Stock bas" : "Normal";
        return new ProductStock(e.productName(), e.variantLabel(), e.quantity(), e.minimumThreshold(), level);
    }
}
