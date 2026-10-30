package com.keevo.commerce.sale.adapter.out.export;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import com.keevo.shared.infrastructure.export.NativeKeysetExportDataset;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.keevo.shared.infrastructure.export.ExportValues.actorJoins;
import static com.keevo.shared.infrastructure.export.ExportValues.actorNameSelect;
import static com.keevo.shared.infrastructure.export.ExportValues.frLabel;
import static com.keevo.shared.infrastructure.export.ExportValues.saleReference;
import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code sale_items} (« Lignes de vente ») — Story v1s-18-2. Event dataset filtered
 * through its sale: period on the sale date, store on the sale's store. The product name is the
 * snapshot taken at sale time; the SKU is the current product's (empty when it no longer exists).
 * Keyset {@code (sale date, sale id, item id)}.
 */
@Component
public class SaleItemsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "sale_items";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("sale", "Vente", ExportColumnType.TEXT),
            new ExportColumn("occurredAt", "Date", ExportColumnType.DATETIME),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("employee", "Vendeur", ExportColumnType.TEXT),
            new ExportColumn("saleStatus", "Statut de la vente", ExportColumnType.TEXT),
            new ExportColumn("sku", "Référence", ExportColumnType.TEXT),
            new ExportColumn("product", "Produit", ExportColumnType.TEXT),
            new ExportColumn("quantity", "Quantité", ExportColumnType.INTEGER),
            new ExportColumn("cataloguePrice", "Prix catalogue", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("unitPrice", "Prix appliqué", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("subtotal", "Sous-total", ExportColumnType.AMOUNT_XAF));

    private static final List<Key> KEYSET = List.of(
            new Key(SaleLabels.SALE_DATE, KeyType.TIMESTAMP),
            new Key("s.id", KeyType.UUID),
            new Key("i.id", KeyType.UUID));

    public SaleItemsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String moduleId() { return "pos"; } // Story 13.10a review: owning module
    @Override public String key() { return KEY; }
    @Override public String label() { return "Lignes de vente"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return SaleLabels.SALE_DATE + ", st.name, " + actorNameSelect("a")
                + ", s.status, p.sku, i.product_name, i.quantity,"
                + " i.catalogue_unit_price, i.applied_unit_price, i.subtotal";
    }

    @Override
    protected String fromClause() {
        return "sale_items i JOIN sales s ON s.id = i.sale_id LEFT JOIN stores st ON st.id = s.store_id"
                + " LEFT JOIN products p ON p.id = i.product_id" + actorJoins("s.employee_id", "a");
    }

    @Override
    protected String storePredicate() {
        return "s.store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected String periodExpression() {
        return SaleLabels.SALE_DATE;
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        Instant occurredAt = toInstant(r[0]);
        String store = str(r[1]);
        return Arrays.asList(saleReference(occurredAt, store), occurredAt, store, str(r[2]),
                frLabel(SaleLabels.STATUS, r[3]), str(r[4]), str(r[5]), toLong(r[6]), toLong(r[7]), toLong(r[8]),
                toLong(r[9]));
    }
}
