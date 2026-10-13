package com.keevo.catalog.stock.adapter.out.export;

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

import static com.keevo.shared.infrastructure.export.ExportValues.actorJoins;
import static com.keevo.shared.infrastructure.export.ExportValues.actorNameSelect;
import static com.keevo.shared.infrastructure.export.ExportValues.frLabel;
import static com.keevo.shared.infrastructure.export.ExportValues.notes;
import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code stock_transfers} (« Transferts ») — Story v1s-18-2. Event dataset: period
 * on {@code occurred_at}; the store filter keeps transfers whose source <b>or</b> destination is
 * that store. Keyset {@code (occurred_at, id)}.
 */
@Component
public class StockTransfersExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "stock_transfers";

    static final Map<String, String> STATUS = Map.of(
            "COMPLETED", "Terminé",
            "PENDING_SYNC", "En attente de synchronisation",
            "CONFLICT", "En conflit",
            "IN_TRANSIT", "En transit");

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("occurredAt", "Date", ExportColumnType.DATETIME),
            new ExportColumn("sourceStore", "Boutique d'origine", ExportColumnType.TEXT),
            new ExportColumn("destinationStore", "Boutique de destination", ExportColumnType.TEXT),
            new ExportColumn("sku", "Référence", ExportColumnType.TEXT),
            new ExportColumn("product", "Produit", ExportColumnType.TEXT),
            new ExportColumn("quantity", "Quantité", ExportColumnType.INTEGER),
            new ExportColumn("status", "Statut", ExportColumnType.TEXT),
            new ExportColumn("actor", "Auteur", ExportColumnType.TEXT),
            new ExportColumn("notes", "Notes", ExportColumnType.TEXT));

    private static final List<Key> KEYSET = List.of(
            new Key("t.occurred_at", KeyType.TIMESTAMP),
            new Key("t.id", KeyType.UUID));

    public StockTransfersExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Transferts"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "t.occurred_at, src.name, dst.name, p.sku, p.name, t.quantity, t.status, "
                + actorNameSelect("a") + ", t.notes";
    }

    @Override
    protected String fromClause() {
        return "stock_transfers t LEFT JOIN stores src ON src.id = t.source_store_id"
                + " LEFT JOIN stores dst ON dst.id = t.destination_store_id"
                + " LEFT JOIN products p ON p.id = t.product_id" + actorJoins("t.actor_id", "a");
    }

    @Override
    protected String storePredicate() {
        return "t.source_store_id = CAST(:storeId AS uuid) OR t.destination_store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected String periodExpression() {
        return "t.occurred_at";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(toInstant(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4]),
                toLong(r[5]), frLabel(STATUS, r[6]), str(r[7]), notes(r[8]));
    }
}
