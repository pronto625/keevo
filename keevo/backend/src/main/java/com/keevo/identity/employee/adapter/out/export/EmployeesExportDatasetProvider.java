package com.keevo.identity.employee.adapter.out.export;

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

import static com.keevo.shared.infrastructure.export.ExportValues.frLabel;
import static com.keevo.shared.infrastructure.export.ExportValues.str;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;

/**
 * Export dataset {@code employees} (« Équipe ») — Story v1s-18-2. Reference dataset, store-scoped
 * (store filter on {@code store_id}, period ignored); inactive employees included. No secret is
 * exported: no password hash, lock counters, tokens or password-change flag — the phone comes
 * from {@code public.users}. Keyset {@code (created_at, id)}.
 */
@Component
public class EmployeesExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "employees";

    private static final Map<String, String> STATUS = Map.of("ACTIVE", "Actif", "INACTIVE", "Inactif");

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("firstName", "Prénom", ExportColumnType.TEXT),
            new ExportColumn("lastName", "Nom", ExportColumnType.TEXT),
            new ExportColumn("phone", "Téléphone", ExportColumnType.TEXT),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("status", "Statut", ExportColumnType.TEXT),
            new ExportColumn("createdAt", "Ajouté le", ExportColumnType.DATETIME));

    private static final List<Key> KEYSET = List.of(
            new Key("e.created_at", KeyType.TIMESTAMP),
            new Key("e.id", KeyType.UUID));

    public EmployeesExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Équipe"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "e.first_name, e.last_name, u.phone_number, st.name, e.status, e.created_at";
    }

    @Override
    protected String fromClause() {
        return "employees e LEFT JOIN public.users u ON u.id = e.user_id LEFT JOIN stores st ON st.id = e.store_id";
    }

    @Override
    protected String storePredicate() {
        return "e.store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(str(r[0]), str(r[1]), str(r[2]), str(r[3]), frLabel(STATUS, r[4]),
                toInstant(r[5]));
    }
}
