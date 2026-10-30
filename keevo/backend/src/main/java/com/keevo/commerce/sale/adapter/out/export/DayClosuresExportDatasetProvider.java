package com.keevo.commerce.sale.adapter.out.export;

import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportColumnType;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.export.KeysetCursor.KeyType;
import com.keevo.shared.infrastructure.export.NativeKeysetExportDataset;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

import static com.keevo.shared.infrastructure.export.ExportValues.actorJoins;
import static com.keevo.shared.infrastructure.export.ExportValues.actorNameSelect;
import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toBool;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLocalDate;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code day_closures} (« Clôtures ») — Story v1s-18-2. Event dataset: period on
 * {@code closed_at}, store on {@code store_id}. Keyset {@code (closed_at, id)}.
 */
@Component
public class DayClosuresExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "day_closures";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("closureDate", "Jour", ExportColumnType.DATE),
            new ExportColumn("closedAt", "Clôturée le", ExportColumnType.DATETIME),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("automatic", "Automatique", ExportColumnType.BOOLEAN),
            new ExportColumn("actor", "Clôturée par", ExportColumnType.TEXT),
            new ExportColumn("salesCount", "Nombre de ventes", ExportColumnType.INTEGER),
            new ExportColumn("revenue", "Chiffre d'affaires", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("cashAmount", "Espèces", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("momoAmount", "Mobile Money", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("pendingSalesCount", "Ventes en attente", ExportColumnType.INTEGER),
            new ExportColumn("pendingSalesTotal", "Montant en attente", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("topProduct", "Meilleur produit", ExportColumnType.TEXT),
            new ExportColumn("topProductQuantity", "Quantité meilleur produit", ExportColumnType.INTEGER));

    private static final List<Key> KEYSET = List.of(
            new Key("d.closed_at", KeyType.TIMESTAMP),
            new Key("d.id", KeyType.UUID));

    public DayClosuresExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String moduleId() { return "pos"; } // Story 13.10a review: owning module
    @Override public String key() { return KEY; }
    @Override public String label() { return "Clôtures"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    /** Closure id, after the columns: the « Fin de journée » PDF loads each closure by id. */
    @Override public List<String> internalKeys() { return INTERNAL; }

    public static final String INTERNAL_ID = "id";
    private static final List<String> INTERNAL = List.of(INTERNAL_ID);

    @Override
    protected String selectList(ExportFilter filter) {
        return "d.closure_date, d.closed_at, st.name, d.is_automatic, " + actorNameSelect("a")
                + ", d.total_sales, d.total_revenue, d.cash_amount, d.momo_amount, d.pending_sales_count,"
                + " d.pending_sales_total, d.top_product_name, d.top_product_qty, CAST(d.id AS text)";
    }

    @Override
    protected String fromClause() {
        return "day_closures d LEFT JOIN stores st ON st.id = d.store_id" + actorJoins("d.actor_id", "a");
    }

    @Override
    protected String storePredicate() {
        return "d.store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected String periodExpression() {
        return "d.closed_at";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(toLocalDate(r[0]), toInstant(r[1]), str(r[2]), toBool(r[3]), str(r[4]),
                toLong(r[5]), toLong(r[6]), toLong(r[7]), toLong(r[8]), toLong(r[9]), toLong(r[10]),
                str(r[11]), toLong(r[12]), str(r[13]));
    }
}
