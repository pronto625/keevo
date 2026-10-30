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
 * Export dataset {@code suppliers} (« Fournisseurs ») — Story v1s-18-2. Reference dataset, not
 * store-scoped (no store or period filter); archived suppliers included. Keyset
 * {@code (created_at, id)}.
 */
@Component
public class SuppliersExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "suppliers";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("phone", "Téléphone", ExportColumnType.TEXT),
            new ExportColumn("email", "E-mail", ExportColumnType.TEXT),
            new ExportColumn("productCount", "Produits fournis", ExportColumnType.INTEGER),
            new ExportColumn("archived", "Archivé", ExportColumnType.BOOLEAN),
            new ExportColumn("createdAt", "Créé le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("s.created_at", KeyType.TIMESTAMP),
            new Key("s.id", KeyType.UUID));

    public SuppliersExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String moduleId() { return "crm"; } // Story 13.10a review: owning module
    @Override public String key() { return KEY; }
    @Override public String label() { return "Fournisseurs"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "s.name, s.phone, s.email,"
                + " (SELECT COUNT(*) FROM product_suppliers ps WHERE ps.supplier_id = s.id), s.archived, s.created_at";
    }

    @Override
    protected String fromClause() {
        return "suppliers s";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), toLong(r[3]), toBool(r[4]),
                toInstant(r[5]));
    }
}
