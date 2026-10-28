package com.keevo.inventory.stock.adapter.out.export;

import com.keevo.inventory.stock.domain.entity.MovementType;
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
 * Export dataset {@code stock_movements} (« Mouvements de stock ») — Story v1s-18-2. Event
 * dataset: period on {@code occurred_at}, store on {@code store_id}. Keyset
 * {@code (occurred_at, id)}.
 */
@Component
public class StockMovementsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "stock_movements";

    static final Map<String, String> TYPES = Map.of(
            MovementType.SALE.name(), "Vente",
            MovementType.STOCK_ENTRY.name(), "Entrée de stock",
            MovementType.TRANSFER_IN.name(), "Transfert entrant",
            MovementType.TRANSFER_OUT.name(), "Transfert sortant",
            MovementType.ADJUSTMENT.name(), "Ajustement",
            MovementType.SALE_CANCELLED.name(), "Annulation de vente");

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("occurredAt", "Date", ExportColumnType.DATETIME),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("sku", "Référence", ExportColumnType.TEXT),
            new ExportColumn("product", "Produit", ExportColumnType.TEXT),
            new ExportColumn("type", "Type", ExportColumnType.TEXT),
            new ExportColumn("quantityBefore", "Quantité avant", ExportColumnType.INTEGER),
            new ExportColumn("quantityChange", "Variation", ExportColumnType.INTEGER),
            new ExportColumn("quantityAfter", "Quantité après", ExportColumnType.INTEGER),
            new ExportColumn("actor", "Auteur", ExportColumnType.TEXT),
            new ExportColumn("notes", "Notes", ExportColumnType.TEXT));

    private static final List<Key> KEYSET = List.of(
            new Key("m.occurred_at", KeyType.TIMESTAMP),
            new Key("m.id", KeyType.UUID));

    public StockMovementsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Mouvements de stock"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "m.occurred_at, st.name, p.sku, p.name, m.movement_type,"
                + " m.quantity_before, m.quantity_change, m.quantity_after, " + actorNameSelect("a") + ", m.notes";
    }

    @Override
    protected String fromClause() {
        return "stock_movements m LEFT JOIN stores st ON st.id = m.store_id"
                + " LEFT JOIN products p ON p.id = m.product_id" + actorJoins("m.actor_id", "a");
    }

    @Override
    protected String storePredicate() {
        return "m.store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected String periodExpression() {
        return "m.occurred_at";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(toInstant(r[0]), str(r[1]), str(r[2]), str(r[3]), frLabel(TYPES, r[4]),
                toLong(r[5]), toLong(r[6]), toLong(r[7]), str(r[8]), notes(r[9]));
    }
}
