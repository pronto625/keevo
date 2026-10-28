package com.keevo.inventory.stock.adapter.out.export;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import com.keevo.shared.infrastructure.export.NativeKeysetExportDataset;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toBool;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code stock_levels} (« Stocks ») — Story v1s-18-2. Reference dataset (current
 * stock), store-scoped: store filter on {@code store_id}, period ignored. Levels of archived
 * products are kept, flagged « Produit archivé ». Threshold = the store level's own threshold when
 * set, else the product's. Keyset {@code (store name, product name, sl.id)}.
 */
@Component
public class StockLevelsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "stock_levels";
    static final String OUT_OF_STOCK = "Rupture";
    static final String LOW_STOCK = "Stock bas";
    static final String OK = "OK";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("sku", "Référence", ExportColumnType.TEXT),
            new ExportColumn("product", "Produit", ExportColumnType.TEXT),
            new ExportColumn("category", "Catégorie", ExportColumnType.TEXT),
            new ExportColumn("quantity", "Quantité", ExportColumnType.INTEGER),
            new ExportColumn("minimumThreshold", "Seuil minimum", ExportColumnType.INTEGER),
            new ExportColumn("stockStatus", "État", ExportColumnType.TEXT),
            new ExportColumn("buyPrice", "Prix d'achat", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("stockValue", "Valeur du stock", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("productArchived", "Produit archivé", ExportColumnType.BOOLEAN),
            new ExportColumn("updatedAt", "Mis à jour le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("COALESCE(st.name, '')", KeyType.TEXT),
            new Key("COALESCE(p.name, '')", KeyType.TEXT),
            new Key("sl.id", KeyType.UUID));

    public StockLevelsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Stocks"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    /** Store id, after the columns: the PDF groups by store id (two stores may share a name). */
    @Override public List<String> internalKeys() { return INTERNAL; }

    static final String INTERNAL_STORE_ID = "storeId";
    private static final List<String> INTERNAL = List.of(INTERNAL_STORE_ID);

    @Override
    protected String selectList(ExportFilter filter) {
        return "st.name, p.sku, p.name, c.name,"
                + " sl.quantity, COALESCE(NULLIF(sl.minimum_threshold, 0), p.minimum_threshold, 0),"
                + " p.buy_price, p.archived, sl.updated_at, CAST(sl.store_id AS text)";
    }

    @Override
    protected String fromClause() {
        return "stock_levels sl LEFT JOIN stores st ON st.id = sl.store_id"
                + " LEFT JOIN products p ON p.id = sl.product_id"
                + " LEFT JOIN categories c ON c.id = p.category_id";
    }

    @Override
    protected String storePredicate() {
        return "sl.store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        Long quantity = toLong(r[4]);
        Long threshold = toLong(r[5]);
        Long buyPrice = toLong(r[6]);
        long q = quantity != null ? quantity : 0L;
        long value = q <= 0 || buyPrice == null ? 0L : q * buyPrice;
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), str(r[3]),
                quantity, threshold, stockStatus(q, threshold != null ? threshold : 0L), buyPrice, value,
                toBool(r[7]), toInstant(r[8]), str(r[9]));
    }

    static String stockStatus(long quantity, long threshold) {
        if (quantity <= 0) return OUT_OF_STOCK;
        if (threshold > 0 && quantity <= threshold) return LOW_STOCK;
        return OK;
    }
}
