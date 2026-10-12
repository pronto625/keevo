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
 * Export dataset {@code inventory_sessions} (« Inventaires ») — Story v1s-18-2. Event dataset:
 * period on {@code started_at}, store on {@code store_id}; cancelled sessions included. Counted /
 * gap figures cover the counted lines only ({@code physical} not null); total gap = Σ (physical −
 * theoretical). Keyset {@code (started_at, id)}.
 */
@Component
public class InventorySessionsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "inventory_sessions";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("id", "Identifiant", ExportColumnType.TEXT),
            new ExportColumn("startedAt", "Commencé le", ExportColumnType.DATETIME),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("scope", "Périmètre", ExportColumnType.TEXT),
            new ExportColumn("status", "Statut", ExportColumnType.TEXT),
            new ExportColumn("startedBy", "Commencé par", ExportColumnType.TEXT),
            new ExportColumn("completedAt", "Validé le", ExportColumnType.DATETIME),
            new ExportColumn("cancelledAt", "Annulé le", ExportColumnType.DATETIME),
            new ExportColumn("countedProducts", "Produits comptés", ExportColumnType.INTEGER),
            new ExportColumn("gapProducts", "Produits en écart", ExportColumnType.INTEGER),
            new ExportColumn("totalGap", "Écart total", ExportColumnType.INTEGER));

    private static final List<Key> KEYSET = List.of(
            new Key("s.started_at", KeyType.TIMESTAMP),
            new Key("s.id", KeyType.UUID));

    public InventorySessionsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Inventaires"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "CAST(s.id AS text), s.started_at, st.name, s.scope, s.status, " + actorNameSelect("a")
                + ", s.completed_at, s.cancelled_at, agg.counted, agg.gaps, agg.total_gap";
    }

    @Override
    protected String fromClause() {
        return "inventory_sessions s LEFT JOIN stores st ON st.id = s.store_id"
                + " LEFT JOIN LATERAL (SELECT COUNT(*) FILTER (WHERE ic.physical IS NOT NULL) AS counted,"
                + " COUNT(*) FILTER (WHERE ic.physical IS NOT NULL AND ic.physical <> ic.theoretical) AS gaps,"
                + " COALESCE(SUM(ic.physical - ic.theoretical) FILTER (WHERE ic.physical IS NOT NULL), 0) AS total_gap"
                + " FROM inventory_counts ic WHERE ic.session_id = s.id) agg ON TRUE"
                + actorJoins("s.started_by", "a");
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
        return Arrays.asList(str(r[0]), toInstant(r[1]), str(r[2]), frLabel(InventoryLabels.SCOPE, r[3]),
                frLabel(InventoryLabels.STATUS, r[4]), str(r[5]), toInstant(r[6]), toInstant(r[7]),
                toLong(r[8]), toLong(r[9]), toLong(r[10]));
    }
}
