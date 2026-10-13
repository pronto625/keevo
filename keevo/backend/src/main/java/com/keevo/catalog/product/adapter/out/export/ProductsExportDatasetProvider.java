package com.keevo.catalog.product.adapter.out.export;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import com.keevo.shared.infrastructure.export.NativeKeysetExportDataset;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.keevo.shared.infrastructure.export.ExportValues.frLabel;
import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toBool;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code products} (« Produits ») — Story v1s-18-2, FR94.
 *
 * <p>Reference dataset (snapshot of the catalogue, archived and draft products included): the
 * period is ignored; a store filter does not drop products, it narrows the {@code stockQuantity}
 * column to that store's quantity (otherwise the sum over every store). Keyset
 * {@code (p.created_at, p.id)}.
 */
@Component
public class ProductsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "products";

    private static final Map<String, String> STATUS = Map.of("ACTIVE", "Actif", "DRAFT", "Brouillon");

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("sku", "Référence", ExportColumnType.TEXT),
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("category", "Catégorie", ExportColumnType.TEXT),
            new ExportColumn("status", "Statut", ExportColumnType.TEXT),
            new ExportColumn("salePrice", "Prix de vente", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("buyPrice", "Prix d'achat", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("transportCost", "Frais de transport", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("minimumThreshold", "Seuil minimum", ExportColumnType.INTEGER),
            new ExportColumn("stockQuantity", "Stock", ExportColumnType.INTEGER),
            new ExportColumn("suppliers", "Fournisseurs", ExportColumnType.TEXT),
            new ExportColumn("archived", "Archivé", ExportColumnType.BOOLEAN),
            new ExportColumn("createdAt", "Créé le", ExportColumnType.DATETIME),
            new ExportColumn("updatedAt", "Modifié le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("p.created_at", KeyType.TIMESTAMP),
            new Key("p.id", KeyType.UUID));

    public ProductsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Produits"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    /** The store filter narrows the stock column only — rows are never dropped. */
    @Override
    public boolean supportsStoreFilter() {
        return true;
    }

    @Override
    protected String selectList(ExportFilter filter) {
        String storeClause = filter.storeId() != null ? " AND sl.store_id = CAST(:stockStoreId AS uuid)" : "";
        return "p.sku, p.name, c.name, p.status, p.price, p.buy_price, p.transport_cost,"
                + " p.minimum_threshold,"
                + " (SELECT COALESCE(SUM(sl.quantity), 0) FROM stock_levels sl WHERE sl.product_id = p.id"
                + storeClause + "),"
                + " (SELECT STRING_AGG(sup.name, ', ' ORDER BY sup.name) FROM product_suppliers ps"
                + " JOIN suppliers sup ON sup.id = ps.supplier_id WHERE ps.product_id = p.id),"
                + " p.archived, p.created_at, p.updated_at";
    }

    @Override
    protected Map<String, Object> selectParameters(ExportFilter filter) {
        return filter.storeId() != null ? Map.of("stockStoreId", filter.storeId().toString()) : Map.of();
    }

    @Override
    protected String fromClause() {
        return "products p LEFT JOIN categories c ON c.id = p.category_id";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), frLabel(STATUS, r[3]),
                toLong(r[4]), toLong(r[5]), toLong(r[6]), toLong(r[7]), toLong(r[8]), str(r[9]),
                toBool(r[10]), toInstant(r[11]), toInstant(r[12]));
    }
}
