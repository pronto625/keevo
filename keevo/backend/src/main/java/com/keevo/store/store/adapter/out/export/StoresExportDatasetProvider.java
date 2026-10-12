package com.keevo.store.store.adapter.out.export;

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
 * Export dataset {@code stores} (« Boutiques ») — Story v1s-18-1, on the shared keyset base since
 * v1s-18-2. Reference dataset: store filter on {@code id}, period ignored (snapshot of the current
 * stores, inactive ones included). Keyset {@code (created_at, id)}.
 */
@Component
public class StoresExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "stores";

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("id", "Identifiant", ExportColumnType.TEXT),
            new ExportColumn("name", "Nom", ExportColumnType.TEXT),
            new ExportColumn("type", "Type", ExportColumnType.TEXT),
            new ExportColumn("address", "Adresse", ExportColumnType.TEXT),
            new ExportColumn("phone", "Téléphone", ExportColumnType.TEXT),
            new ExportColumn("active", "Active", ExportColumnType.BOOLEAN),
            new ExportColumn("createdAt", "Créée le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("s.created_at", KeyType.TIMESTAMP),
            new Key("s.id", KeyType.UUID));

    public StoresExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Boutiques";
    }

    @Override
    public List<ExportColumn> columns() {
        return COLUMNS;
    }

    @Override
    protected String selectList(ExportFilter filter) {
        return "CAST(s.id AS text), s.name, s.type, s.address, s.phone, s.is_active, s.created_at";
    }

    @Override
    protected String fromClause() {
        return "stores s";
    }

    @Override
    protected String storePredicate() {
        return "s.id = CAST(:storeId AS uuid)";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), str(r[3]), str(r[4]), toBool(r[5]), toInstant(r[6]));
    }
}
