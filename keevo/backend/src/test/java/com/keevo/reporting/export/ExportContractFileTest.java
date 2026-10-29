package com.keevo.reporting.export;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.application.port.export.ExportColumn;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.infrastructure.export.AuditLabels;
import com.keevo.shared.infrastructure.export.ExportValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story v1s-18-5 — the shared export contract file {@code keevo/contracts/export-datasets.json}.
 *
 * <p>The on-device CSV export of the app must produce the same columns (keys, French labels,
 * types), filters and code→French label maps as the server providers. The contract file is the
 * single place both sides are checked against: this test asserts that the file equals what the
 * real providers declare (metadata, columns, the {@code static Map<String,String>} label maps read
 * by reflection, {@link ExportValues#OWNER_LABEL} and the stock state strings), and the app test
 * {@code test/features/export/domain/local_export_contract_test.dart} asserts that the Dart
 * catalogue equals the file.
 *
 * <p>When a provider changes on purpose, regenerate the file instead of editing it by hand:
 * <pre>
 * cd keevo/backend
 * mvn -o test -Dtest=ExportContractFileTest -Dexport.contract.write=true
 * </pre>
 * then update the app catalogue ({@code local_export_schema.dart}) until the app test passes.
 */
@DisplayName("Export contract file — keevo/contracts/export-datasets.json (v1s-18-5)")
class ExportContractFileTest {

    private static final Path CONTRACT = Path.of("..", "contracts", "export-datasets.json");

    private static final String SALE = "com.keevo.commerce.sale.adapter.out.export.";
    private static final String INVENTORY = "com.keevo.inventory.counting.adapter.out.export.";
    private static final String AUDIT_LABELS = "com.keevo.shared.infrastructure.export.AuditLabels";

    /** Contract label entry « dataset.columnKey » → (class, static field) holding the map. */
    private static final Map<String, String[]> LABEL_FIELDS = new LinkedHashMap<>();

    static {
        LABEL_FIELDS.put("products.status",
                new String[]{"com.keevo.catalog.product.adapter.out.export.ProductsExportDatasetProvider", "STATUS"});
        LABEL_FIELDS.put("stores.type",
                new String[]{"com.keevo.store.store.adapter.out.export.StoresExportDatasetProvider", "TYPES"});
        LABEL_FIELDS.put("stock_movements.type",
                new String[]{"com.keevo.inventory.stock.adapter.out.export.StockMovementsExportDatasetProvider", "TYPES"});
        LABEL_FIELDS.put("stock_transfers.status",
                new String[]{"com.keevo.inventory.transfer.adapter.out.export.StockTransfersExportDatasetProvider", "STATUS"});
        LABEL_FIELDS.put("sales.status", new String[]{SALE + "SaleLabels", "STATUS"});
        LABEL_FIELDS.put("sales.paymentMode", new String[]{SALE + "SaleLabels", "PAYMENT_MODE"});
        LABEL_FIELDS.put("sale_items.saleStatus", new String[]{SALE + "SaleLabels", "STATUS"});
        LABEL_FIELDS.put("inventory_sessions.scope", new String[]{INVENTORY + "InventoryLabels", "SCOPE"});
        LABEL_FIELDS.put("inventory_sessions.status", new String[]{INVENTORY + "InventoryLabels", "STATUS"});
        LABEL_FIELDS.put("inventory_counts.sessionStatus", new String[]{INVENTORY + "InventoryLabels", "STATUS"});
        LABEL_FIELDS.put("employees.status",
                new String[]{"com.keevo.identity.employee.adapter.out.export.EmployeesExportDatasetProvider", "STATUS"});
        LABEL_FIELDS.put("reports.type",
                new String[]{"com.keevo.reporting.report.adapter.out.export.ReportsExportDatasetProvider", "TYPES"});
        LABEL_FIELDS.put("reports.deliveryStatus",
                new String[]{"com.keevo.reporting.report.adapter.out.export.ReportsExportDatasetProvider", "DELIVERY"});
        LABEL_FIELDS.put("audit_log.actorRole",
                new String[]{"com.keevo.shared.infrastructure.export.AuditLogExportDatasetProvider", "ROLES"});
        LABEL_FIELDS.put("audit_log.source",
                new String[]{"com.keevo.shared.infrastructure.export.AuditLogExportDatasetProvider", "SOURCES"});
        LABEL_FIELDS.put("audit_log.action", new String[]{AUDIT_LABELS, "ACTIONS"});
        LABEL_FIELDS.put("audit_log.entityType", new String[]{AUDIT_LABELS, "ENTITY_TYPES"});
    }

    /**
     * Story v1s-16-20 — top-level {@code audit} section: the action history rendering table
     * (field labels, code values, context labels, hidden keys) the app AuditPresenter mirrors.
     */
    private static final Map<String, String[]> AUDIT_FIELDS = new LinkedHashMap<>();

    static {
        AUDIT_FIELDS.put("fields", new String[]{AUDIT_LABELS, "FIELDS"});
        AUDIT_FIELDS.put("values", new String[]{AUDIT_LABELS, "VALUES"});
        AUDIT_FIELDS.put("context", new String[]{AUDIT_LABELS, "CONTEXT"});
    }

    /** Label holder classes that are not providers (scanned for unlisted maps too). */
    private static final List<String> LABEL_CLASSES = List.of(SALE + "SaleLabels", INVENTORY + "InventoryLabels",
            AUDIT_LABELS);

    private static final String STOCK_LEVELS =
            "com.keevo.inventory.stock.adapter.out.export.StockLevelsExportDatasetProvider";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the contract file equals the providers' schema and label maps")
    void contractFileMatchesProviders() throws Exception {
        Map<String, Object> expected = expectedContract();
        if (Boolean.getBoolean("export.contract.write")) {
            write(expected);
        }
        assertThat(Files.exists(CONTRACT))
                .as("missing %s — run with -Dexport.contract.write=true", CONTRACT.toAbsolutePath())
                .isTrue();
        JsonNode actual = mapper.readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));
        assertThat(actual).isEqualTo(mapper.valueToTree(expected));
    }

    @Test
    @DisplayName("every static Map<String,String> of a provider / labels class is listed in the contract")
    void everyLabelMapIsListed() throws Exception {
        Set<String> listed = new LinkedHashSet<>();
        LABEL_FIELDS.values().forEach(f -> listed.add(f[0] + "#" + f[1]));
        AUDIT_FIELDS.values().forEach(f -> listed.add(f[0] + "#" + f[1]));
        List<Class<?>> classes = new ArrayList<>();
        for (ExportDatasetProvider p : ExportDatasetCatalogTest.allProviders()) {
            classes.add(p.getClass());
        }
        for (String name : LABEL_CLASSES) {
            classes.add(Class.forName(name));
        }
        for (Class<?> type : classes) {
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && isStringMap(f.getGenericType())) {
                    assertThat(listed).as("label map %s#%s is not in the contract mapping table",
                            type.getName(), f.getName()).contains(type.getName() + "#" + f.getName());
                }
            }
        }
    }

    // ── Expected contract ──────────────────────────────────────────────────────

    private Map<String, Object> expectedContract() throws Exception {
        Map<String, ExportDatasetProvider> byKey = new LinkedHashMap<>();
        for (ExportDatasetProvider p : ExportDatasetCatalogTest.allProviders()) {
            byKey.put(p.key(), p);
        }
        List<Object> datasets = new ArrayList<>();
        for (String key : ExportDatasetCatalogTest.goldenKeys()) {
            ExportDatasetProvider p = byKey.get(key);
            assertThat(p).as("provider %s", key).isNotNull();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("key", p.key());
            d.put("label", p.label());
            d.put("storeFilter", p.supportsStoreFilter());
            d.put("periodFilter", p.supportsPeriodFilter());
            d.put("criteria", p.criteriaKeys().stream().sorted().toList());
            List<Object> columns = new ArrayList<>();
            for (ExportColumn c : p.columns()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("key", c.key());
                col.put("label", c.label());
                col.put("type", c.type().name());
                columns.add(col);
            }
            d.put("columns", columns);
            datasets.add(d);
        }
        assertThat(byKey.keySet()).containsExactlyInAnyOrderElementsOf(ExportDatasetCatalogTest.goldenKeys());

        Map<String, Object> labels = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : LABEL_FIELDS.entrySet()) {
            labels.put(e.getKey(), new TreeMap<>(readMap(e.getValue()[0], e.getValue()[1])));
        }

        Map<String, Object> constants = new LinkedHashMap<>();
        constants.put("ownerLabel", ExportValues.OWNER_LABEL);
        constants.put("stockStatus", List.of(
                readString(STOCK_LEVELS, "OUT_OF_STOCK"),
                readString(STOCK_LEVELS, "LOW_STOCK"),
                readString(STOCK_LEVELS, "OK")));

        Map<String, Object> audit = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : AUDIT_FIELDS.entrySet()) {
            audit.put(e.getKey(), new TreeMap<>(readMap(e.getValue()[0], e.getValue()[1])));
        }
        audit.put("hiddenKeys", AuditLabels.HIDDEN_KEYS.stream().sorted().toList());
        audit.put("codeKeys", AuditLabels.CODE_KEYS.stream().sorted().toList());

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("datasets", datasets);
        contract.put("labels", labels);
        contract.put("constants", constants);
        contract.put("audit", audit);
        return contract;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readMap(String className, String field) throws Exception {
        Field f = Class.forName(className).getDeclaredField(field);
        assertThat(Modifier.isStatic(f.getModifiers())).as(className + "#" + field).isTrue();
        assertThat(isStringMap(f.getGenericType())).as(className + "#" + field).isTrue();
        f.setAccessible(true);
        return (Map<String, String>) f.get(null);
    }

    private static String readString(String className, String field) throws Exception {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static boolean isStringMap(Type type) {
        if (!(type instanceof ParameterizedType pt) || pt.getRawType() != Map.class) return false;
        Type[] args = pt.getActualTypeArguments();
        return args.length == 2 && args[0] == String.class && args[1] == String.class;
    }

    private void write(Map<String, Object> contract) throws Exception {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        String json = mapper.writer(printer).writeValueAsString(contract) + "\n";
        Files.createDirectories(CONTRACT.toAbsolutePath().getParent());
        Files.writeString(CONTRACT, json, StandardCharsets.UTF_8);
    }
}
