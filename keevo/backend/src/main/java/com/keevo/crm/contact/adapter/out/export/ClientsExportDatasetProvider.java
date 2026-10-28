package com.keevo.crm.contact.adapter.out.export;

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
 * Export dataset {@code clients} (« Clients ») — Story v1s-18-2. Reference dataset, not
 * store-scoped (no store or period filter); archived clients included. Purchases = COMPLETED
 * sales of the client. Keyset {@code (created_at, id)}.
 */
@Component
public class ClientsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "clients";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("phone", "Téléphone", ExportColumnType.TEXT),
            new ExportColumn("email", "E-mail", ExportColumnType.TEXT),
            new ExportColumn("notes", "Notes", ExportColumnType.TEXT),
            new ExportColumn("salesCount", "Nombre d'achats", ExportColumnType.INTEGER),
            new ExportColumn("totalSpent", "Total des achats", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("lastPurchaseAt", "Dernier achat", ExportColumnType.DATETIME),
            new ExportColumn("archived", "Archivé", ExportColumnType.BOOLEAN),
            new ExportColumn("createdAt", "Créé le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("c.created_at", KeyType.TIMESTAMP),
            new Key("c.id", KeyType.UUID));

    public ClientsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Clients"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "c.name, c.phone, c.email, c.notes, agg.sales_count, agg.total_spent,"
                + " agg.last_purchase, c.archived, c.created_at";
    }

    @Override
    protected String fromClause() {
        return "clients c LEFT JOIN LATERAL (SELECT COUNT(*) AS sales_count,"
                + " COALESCE(SUM(s.total_amount), 0) AS total_spent,"
                + " MAX(COALESCE(s.occurred_at, s.created_at)) AS last_purchase"
                + " FROM sales s WHERE s.client_id = c.id AND s.status = 'COMPLETED') agg ON TRUE";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), str(r[3]), toLong(r[4]), toLong(r[5]),
                toInstant(r[6]), toBool(r[7]), toInstant(r[8]));
    }
}
