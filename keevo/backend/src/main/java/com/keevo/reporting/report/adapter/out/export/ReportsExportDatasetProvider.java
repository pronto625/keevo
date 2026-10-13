package com.keevo.reporting.report.adapter.out.export;

import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.ReportType;
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
import static com.keevo.shared.infrastructure.export.ExportValues.toBool;
import static com.keevo.shared.infrastructure.export.ExportValues.toInstant;
import static com.keevo.shared.infrastructure.export.ExportValues.toLocalDate;
import static com.keevo.shared.infrastructure.export.ExportValues.toLong;

/**
 * Export dataset {@code reports} (« Rapports ») — Story v1s-18-2. Event dataset: period on
 * {@code created_at}, store on {@code store_id}. Store name: the current store's, else the name
 * stored with the report. Keyset {@code (created_at, id)}.
 */
@Component
public class ReportsExportDatasetProvider extends NativeKeysetExportDataset {

    static final String KEY = "reports";

    static final Map<String, String> TYPES = Map.of(
            ReportType.DAILY.name(), "Fin de journée",
            ReportType.DAILY_COMBINED.name(), "Fin de journée (toutes boutiques)",
            ReportType.WEEKLY.name(), "Hebdomadaire");

    static final Map<String, String> DELIVERY = Map.of(
            DeliveryStatus.PENDING.name(), "En attente",
            DeliveryStatus.SENT.name(), "Envoyé",
            DeliveryStatus.FAILED.name(), "Échec de l'envoi",
            DeliveryStatus.IN_APP_ONLY.name(), "Dans l'application uniquement");

    private static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("reportDate", "Date du rapport", ExportColumnType.DATE),
            new ExportColumn("type", "Type", ExportColumnType.TEXT),
            new ExportColumn("store", "Boutique", ExportColumnType.TEXT),
            new ExportColumn("revenue", "Chiffre d'affaires", ExportColumnType.AMOUNT_XAF),
            new ExportColumn("salesCount", "Nombre de ventes", ExportColumnType.INTEGER),
            new ExportColumn("automatic", "Automatique", ExportColumnType.BOOLEAN),
            new ExportColumn("actor", "Généré par", ExportColumnType.TEXT),
            new ExportColumn("deliveryStatus", "Envoi", ExportColumnType.TEXT),
            new ExportColumn("createdAt", "Généré le", ExportColumnType.DATETIME),
            new ExportColumn("content", "Contenu", ExportColumnType.TEXT));

    private static final List<Key> KEYSET = List.of(
            new Key("r.created_at", KeyType.TIMESTAMP),
            new Key("r.id", KeyType.UUID));

    public ReportsExportDatasetProvider(EntityManager em) {
        super(em);
    }

    @Override public String key() { return KEY; }
    @Override public String label() { return "Rapports"; }
    @Override public List<ExportColumn> columns() { return COLUMNS; }

    @Override
    protected String selectList(ExportFilter filter) {
        return "r.report_date, r.report_type, COALESCE(st.name, r.store_name), r.total_revenue,"
                + " r.total_sales, r.is_automatic, r.actor_name, r.delivery_status, r.created_at, r.content";
    }

    @Override
    protected String fromClause() {
        return "reports r LEFT JOIN stores st ON st.id = r.store_id";
    }

    @Override
    protected String storePredicate() {
        return "r.store_id = CAST(:storeId AS uuid)";
    }

    @Override
    protected String periodExpression() {
        return "r.created_at";
    }

    @Override
    protected List<Key> keyset() {
        return KEYSET;
    }

    @Override
    protected List<Object> mapRow(Object[] r) {
        return Arrays.asList(toLocalDate(r[0]), frLabel(TYPES, r[1]), str(r[2]), toLong(r[3]),
                toLong(r[4]), toBool(r[5]), str(r[6]), frLabel(DELIVERY, r[7]), toInstant(r[8]), str(r[9]));
    }
}
