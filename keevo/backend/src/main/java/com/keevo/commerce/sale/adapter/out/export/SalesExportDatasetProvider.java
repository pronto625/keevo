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
 * Export dataset {@code sales} (« Ventes ») — Story v1s-18-2. Event dataset: period on
 * {@code COALESCE(occurred_at, created_at)}, store on {@code store_id}; cancelled and pending sales
 * included with their status. {@code employee_id} is a user id. Keyset {@code (sale date, id)}.
 */
@Component
public class SalesExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "sales";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("reference", "Référence de la vente", ExportColumnType.TEXT),
            new ExportColumn("occurredAt", "Date", ExportColumnType.DATETIME),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("employee", "Vendeur", ExportColumnType.TEXT),
            new ExportColumn("client", "Client", ExportColumnType.TEXT),
            new ExportColumn("status", "Statut", ExportColumnType.TEXT),
            new ExportColumn("paymentMode", "Mode de paiement", ExportColumnType.TEXT),
            new ExportColumn("mobileMoneyRef", "Référence Mobile Money", ExportColumnType.TEXT),
            new ExportColumn("itemCount", "Articles", ExportColumnType.INTEGER),
            new ExportColumn("discount", "Remise", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("total", "Montant total", ExportColumnType.AMOUNT_XAF));

    private static final List<Key> KEYSET = List.of(
            new Key(SaleLabels.SALE_DATE, KeyType.TIMESTAMP),
            new Key("s.id", KeyType.UUID));

    public SalesExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String moduleId() { return "pos"; } // Story 13.10a review: owning module
    @Override public String key() { return KEY; }
    @Override public String label() { return "Ventes"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return SaleLabels.SALE_DATE + ", st.name, " + actorNameSelect("a")
                + ", cl.name, s.status, s.payment_mode, s.mobile_money_ref,"
                + " (SELECT COALESCE(SUM(si.quantity), 0) FROM sale_items si WHERE si.sale_id = s.id),"
                + " s.discount_amount, s.total_amount";
    }

    @Override
    protected String fromClause() {
        return "sales s LEFT JOIN stores st ON st.id = s.store_id LEFT JOIN clients cl ON cl.id = s.client_id"
                + actorJoins("s.employee_id", "a");
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
        return Arrays.asList(saleReference(occurredAt, store), occurredAt, store, str(r[2]), str(r[3]),
                frLabel(SaleLabels.STATUS, r[4]), frLabel(SaleLabels.PAYMENT_MODE, r[5]), str(r[6]),
                toLong(r[7]), toLong(r[8]), toLong(r[9]));
    }
}
