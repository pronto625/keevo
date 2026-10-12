package com.keevo.catalog.category.adapter.out.export;

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

/**
 * Export dataset {@code categories} (« Catégories ») — Story v1s-18-2. Reference dataset, not
 * store-scoped: no store or period filter; inactive categories included. Keyset
 * {@code (created_at, id)}.
 */
@Component
public class CategoriesExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "categories";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("id", "Identifiant", ExportColumnType.TEXT),
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("parent", "Catégorie parente", ExportColumnType.TEXT),
            new ExportColumn("active", "Active", ExportColumnType.BOOLEAN),
            new ExportColumn("custom", "Personnalisée", ExportColumnType.BOOLEAN),
            new ExportColumn("createdAt", "Créée le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("c.created_at", KeyType.TIMESTAMP),
            new Key("c.id", KeyType.UUID));

    public CategoriesExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Catégories"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "CAST(c.id AS text), c.name, pc.name, c.is_active, c.is_custom, c.created_at";
    }

    @Override
    protected String fromClause() {
        return "categories c LEFT JOIN categories pc ON pc.id = c.parent_id";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), toBool(r[3]), toBool(r[4]), toInstant(r[5]));
    }
}
