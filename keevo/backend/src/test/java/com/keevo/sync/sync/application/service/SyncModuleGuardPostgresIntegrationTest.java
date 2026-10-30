package com.keevo.sync.sync.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 12.7 — module-aware push through the real HTTP chain ({@code POST /api/v1/sync/push}, production
 * security filter, real handlers, {@code ModuleRegistry} on {@code public.tenant_modules}) on PostgreSQL.
 *
 * <p>The tenant-module state port is a spy so the number of {@code tenant_modules} reads per push is counted.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Sync push — module guard, full context (Stories 12.7, 13.5)")
class SyncModuleGuardPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreateProductUseCase createProduct;
    @Autowired List<SyncOperationHandler> handlers;
    @MockitoSpyBean TenantModuleStatePort tenantModuleStatePort;

    private final UUID ownerId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        // Story 13.9: registration provisions the free modules with migrations (crm), which materialises their
        // ACTIVE/FREE row — this test starts from the free rule without any row
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        TenantContext.setCurrentTenant(schema);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ownerId, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        try {
            productId = createProduct.execute(new CreateProductDto("Savon module", null, null, null, 500, null,
                    null, 50, ownerId, 0, null, "OWNER", null, null, null)).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
        // stock in the store sold from, so the sales are APPLIED (not a STOCK_NEGATIVE conflict)
        int updated = jdbc.update("UPDATE \"" + schema + "\".stock_levels SET quantity = 50 "
                + "WHERE product_id = ? AND store_id = ?", productId, storeId);
        if (updated == 0) {
            jdbc.update("INSERT INTO \"" + schema + "\".stock_levels (product_id, store_id, quantity) VALUES (?, ?, 50)",
                    productId, storeId);
        }
        clearInvocations(tenantModuleStatePort);
    }

    @AfterEach
    void tearDown() {
        reset(tenantModuleStatePort);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void moduleRow(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, 'FREE')",
                tenantId, moduleId, status);
    }

    private static Map<String, Object> op(String type, Map<String, Object> payload) {
        Map<String, Object> op = new HashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("operationType", type);
        op.put("entityId", UUID.randomUUID().toString());
        op.put("payload", payload);
        op.put("clientTimestamp", Instant.now().minusSeconds(60).toString());
        return op;
    }

    private static Map<String, Object> createClient(String name) {
        return op("CREATE_CLIENT", Map.of("id", UUID.randomUUID().toString(), "name", name,
                "phone", "+237690000001"));
    }

    private Map<String, Object> createSale() {
        return createSale(null);
    }

    private Map<String, Object> createSale(String clientId) {
        Map<String, Object> payload = new HashMap<>(Map.of(
                "saleId", UUID.randomUUID().toString(),
                "storeId", storeId.toString(),
                "paymentMode", "CASH",
                "items", List.of(Map.of(
                        "productId", productId.toString(),
                        "productName", "Savon module",
                        "catalogueUnitPrice", 500,
                        "appliedUnitPrice", 500,
                        "quantity", 1))));
        if (clientId != null) {
            payload.put("clientId", clientId);
        }
        return op("CREATE_SALE", payload);
    }

    @SafeVarargs
    private ResultActions push(Map<String, Object>... ops) throws Exception {
        return pushAs(jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER"), ops);
    }

    @SafeVarargs
    private ResultActions pushAs(String token, Map<String, Object>... ops) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "deviceId", "device-" + UUID.randomUUID().toString().substring(0, 8),
                "operations", List.of(ops)));
        return mockMvc.perform(post("/api/v1/sync/push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String id(Map<String, Object> op) {
        return (String) op.get("operationId");
    }

    private static String result(String operationId, String field) {
        return "$.data.results[?(@.operationId == '" + operationId + "')]." + field;
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("crm DISABLED: client REJECTED MODULE_INACTIVE (payload in sync_error_log), sale APPLIED; 1 read")
    void crmDisabled_clientRejected_saleApplied_oneRead() throws Exception {
        moduleRow("crm", "DISABLED");
        var client = createClient("Client hors module");
        var sale = createSale();

        push(client, sale)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results", hasSize(2)))
                .andExpect(jsonPath(result(id(client), "status"), contains("REJECTED")))
                .andExpect(jsonPath(result(id(client), "reason"), contains("MODULE_INACTIVE")))
                .andExpect(jsonPath(result(id(sale), "status"), contains("APPLIED")));

        verify(tenantModuleStatePort, times(1)).findRows(anyString());

        Map<String, Object> error = jdbc.queryForMap("SELECT operation_type, error_reason, payload::text AS payload "
                + "FROM \"" + schema + "\".sync_error_log WHERE operation_id = ?", id(client));
        assertThat(error.get("operation_type")).isEqualTo("CREATE_CLIENT");
        assertThat(error.get("error_reason")).isEqualTo("MODULE_INACTIVE");
        assertThat((String) error.get("payload")).contains("Client hors module");
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".sync_operations_log WHERE id = ?",
                String.class, id(client))).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients WHERE name = ?",
                Integer.class, "Client hors module")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".sales", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("13.5 — crm EXPIRED in grace (read-only): client REJECTED MODULE_READ_ONLY, sale APPLIED; 1 read")
    void crmReadOnly_clientRejectedReadOnly_saleApplied() throws Exception {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at, grace_until) "
                + "VALUES (?, 'crm', 'ACTIVE', 'STANDARD', now() - interval '1 hour', now() + interval '7 days')",
                tenantId);
        var client = createClient("Client en lecture seule");
        var sale = createSale();

        push(client, sale)
                .andExpect(status().isOk())
                .andExpect(jsonPath(result(id(client), "status"), contains("REJECTED")))
                .andExpect(jsonPath(result(id(client), "reason"), contains("MODULE_READ_ONLY")))
                .andExpect(jsonPath(result(id(sale), "status"), contains("APPLIED")));

        verify(tenantModuleStatePort, times(1)).findRows(anyString());
        assertThat(jdbc.queryForObject("SELECT error_reason FROM \"" + schema + "\".sync_error_log "
                + "WHERE operation_id = ?", String.class, id(client))).isEqualTo("MODULE_READ_ONLY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients WHERE name = ?",
                Integer.class, "Client en lecture seule")).isZero();
    }

    @Test
    @DisplayName("all modules active (no row): mixed batch applied as today, 1 tenant_modules read for N operations")
    void allActive_mixedBatchApplied_oneRead() throws Exception {
        var c1 = createClient("Client un");
        var c2 = createClient("Client deux");
        var sale = createSale();

        push(c1, c2, sale)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results", hasSize(3)))
                .andExpect(jsonPath("$.data.results[*].status", everyItem(is("APPLIED"))));

        verify(tenantModuleStatePort, times(1)).findRows(anyString());
    }

    @Test
    @DisplayName("replay of an applied operation after its module was switched off: DUPLICATE")
    void replayAfterModuleOff_duplicate() throws Exception {
        var client = createClient("Client rejoué");
        push(client).andExpect(jsonPath(result(id(client), "status"), contains("APPLIED")));

        moduleRow("crm", "DISABLED");
        push(client)
                .andExpect(status().isOk())
                .andExpect(jsonPath(result(id(client), "status"), contains("DUPLICATE")));
        assertThat(jdbc.queryForObject("SELECT status FROM \"" + schema + "\".sync_operations_log WHERE id = ?",
                String.class, id(client))).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("live scenario: crm off, client + sale with that client + sale without client → 200, "
            + "client MODULE_INACTIVE, its sale CLIENT_NOT_FOUND, the other sale APPLIED")
    void crmOff_saleWithHeldClient_rejectedCleanly_otherSaleApplied() throws Exception {
        moduleRow("crm", "DISABLED");
        var client = createClient("Mireille");
        @SuppressWarnings("unchecked")
        String clientId = (String) ((Map<String, Object>) client.get("payload")).get("id");
        var saleWithClient = createSale(clientId);
        var saleWithoutClient = createSale();

        push(client, saleWithClient, saleWithoutClient)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results", hasSize(3)))
                .andExpect(jsonPath(result(id(client), "reason"), contains("MODULE_INACTIVE")))
                .andExpect(jsonPath(result(id(saleWithClient), "status"), contains("REJECTED")))
                .andExpect(jsonPath(result(id(saleWithClient), "reason"), contains("CLIENT_NOT_FOUND")))
                .andExpect(jsonPath(result(id(saleWithoutClient), "status"), contains("APPLIED")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".sales", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT error_reason FROM \"" + schema + "\".sync_error_log "
                + "WHERE operation_id = ?", String.class, id(saleWithClient))).isEqualTo("CLIENT_NOT_FOUND");
    }

    @Test
    @DisplayName("module state unreadable: the push is answered 503, nothing is processed or recorded")
    void registryFailure_503_nothingRecorded() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database down"))
                .when(tenantModuleStatePort).findRows(anyString());
        var client = createClient("Client 503");
        var sale = createSale();

        push(client, sale)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.domainCode", is("SERVICE_UNAVAILABLE")));

        for (String table : List.of("sync_operations_log", "sync_error_log")) {
            String column = table.equals("sync_error_log") ? "operation_id" : "id";
            assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\"." + table + " WHERE " + column
                    + " IN (?, ?)", Integer.class, id(client), id(sale))).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".sales", Integer.class)).isZero();
    }

    @Test
    @DisplayName("platform-admin session: sync push is not allowed (403 before any module read) — unchanged")
    void platformAdmin_pushForbidden() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(UUID.randomUUID(), "public", "SUPER_ADMIN");

        pushAs(token, createClient("Client admin")).andExpect(status().isForbidden());

        verify(tenantModuleStatePort, never()).findRows(anyString());
    }

    @Test
    @DisplayName("every shipped handler declares the module of the spec mapping")
    void handlerModuleMapping() {
        Map<String, String> moduleByType = handlers.stream()
                .flatMap(h -> h.supportedTypes().stream().map(t -> Map.entry(t, h.moduleId())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, String> expected = new HashMap<>();
        Function<String, java.util.function.Consumer<List<String>>> put = module -> types ->
                types.forEach(t -> expected.put(t, module));
        put.apply("pos").accept(List.of("CREATE_SALE", "VALIDATE_SALE", "CANCEL_SALE", "CREATE_DAY_CLOSURE"));
        put.apply("catalog").accept(List.of("CREATE_PRODUCT", "CREATE_DRAFT_PRODUCT", "UPDATE_PRODUCT",
                "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT", "PROMOTE_PRODUCT", "CREATE_CATEGORY", "TOGGLE_CATEGORY",
                "RENAME_CATEGORY"));
        put.apply("crm").accept(List.of("CREATE_CLIENT", "UPDATE_CLIENT", "ARCHIVE_CLIENT", "UNARCHIVE_CLIENT",
                "CREATE_SUPPLIER", "UPDATE_SUPPLIER", "ARCHIVE_SUPPLIER", "UNARCHIVE_SUPPLIER"));
        put.apply("store").accept(List.of("CREATE_STORE", "UPDATE_STORE", "DEACTIVATE_STORE"));
        put.apply("inventory").accept(List.of("STOCK_ADJUST", "RECORD_STOCK_ENTRY", "STOCK_TRANSFER",
                "SAVE_INVENTORY_COUNT", "CREATE_INVENTORY_SESSION", "VALIDATE_INVENTORY"));
        put.apply("core").accept(List.of("CREATE_EMPLOYEE", "REASSIGN_EMPLOYEE", "DEACTIVATE_EMPLOYEE",
                "REACTIVATE_EMPLOYEE", "SUBMIT_FEEDBACK"));

        assertThat(handlers).hasSize(19);
        assertThat(moduleByType).isEqualTo(expected);
    }
}
