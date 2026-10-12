package com.keevo.inventory.counting.adapter.out.export;

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
import static com.keevo.shared.infrastructure.export.ExportValues.frLabel;
import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code inventory_counts} (« Comptages d'inventaire ») — Story v1s-18-2. Event
 * dataset filtered through its session: period on the session's {@code started_at}, store on the
 * session's store. Uncounted lines are exported with an empty count and gap. Keyset
 * {@code (session started_at, session id, count id)}.
 */
@Component
public class InventoryCountsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "inventory_counts";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("sessionId", "Inventaire", ExportColumnType.TEXT),
            new ExportColumn("sessionStartedAt", "Date de l'inventaire", ExportColumnType.DATETIME),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("sessionStatus", "Statut de l'inventaire", ExportColumnType.TEXT),
            new ExportColumn("productId", "Identifiant produit", ExportColumnType.TEXT),
            new ExportColumn("product", "Produit", ExportColumnType.TEXT),
            new ExportColumn("variant", "Variante", ExportColumnType.TEXT),
            new ExportColumn("theoretical", "Stock théorique", ExportColumnType.INTEGER),
            new ExportColumn("physical", "Stock compté", ExportColumnType.INTEGER),
            new ExportColumn("gap", "Écart", ExportColumnType.INTEGER),
            new ExportColumn("countedAt", "Compté le", ExportColumnType.DATETIME),
            new ExportColumn("countedBy", "Compté par", ExportColumnType.TEXT));

    private static final List<Key> KEYSET = List.of(
            new Key("s.started_at", KeyType.TIMESTAMP),
            new Key("s.id", KeyType.UUID),
            new Key("c.id", KeyType.UUID));

    public InventoryCountsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Comptages d'inventaire"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "CAST(s.id AS text), s.started_at, st.name, s.status, CAST(c.product_id AS text), c.product_name,"
                + " c.variant_label, c.theoretical, c.physical, c.physical - c.theoretical, c.counted_at, "
                + actorNameSelect("a");
    }

    @Override
    protected String fromClause() {
        return "inventory_counts c JOIN inventory_sessions s ON s.id = c.session_id"
                + " LEFT JOIN stores st ON st.id = s.store_id" + actorJoins("c.counted_by", "a");
    }

    @Override
    protected String storePredicate() {
        return "s.store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected String periodExpression() {
        return "s.started_at";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), toInstant(r[1]), str(r[2]), frLabel(InventoryLabels.STATUS, r[3]),
                str(r[4]), str(r[5]), str(r[6]), toLong(r[7]), toLong(r[8]), toLong(r[9]), toInstant(r[10]),
                str(r[11]));
    }
}
