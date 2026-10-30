package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.adapter.in.event.SaleInvoicingListener;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story 14.4 (MOD-FR8) — the POS → Invoicing bridge on the real chain: a sale recorded through
 * {@code POST /api/v1/sales} (or a pending sale validated) gets exactly one PAID invoice, asynchronously, through the
 * publication registry; replays never create a second invoice nor burn a number; a tenant without invoicing ENABLED
 * gets nothing (and no table).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("POS → Invoicing bridge — full context (Story 14.4)")
class SaleInvoicingFullContextTest extends FullContextIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final List<String> TABLES = List.of("invoice_counters", "invoice_items", "invoices",
            "invoicing_settings");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreateProductUseCase createProduct;
    @Autowired ModuleRegistry registry;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired SaleInvoicingListener listener;

    private final UUID ownerId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeId;
    private UUID productId;
    private String token;
    private String year;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        TenantContext.setCurrentTenant(schema);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ownerId, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        try {
            productId = createProduct.execute(new CreateProductDto("Savon 14.4", null, null, null, 1000, null,
                    null, 0, ownerId, 0, null, "OWNER", null, null, null)).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
        if (jdbc.update("UPDATE \"" + schema + "\".stock_levels SET quantity = 100 WHERE product_id = ? "
                + "AND store_id = ?", productId, storeId) == 0) {
            jdbc.update("INSERT INTO \"" + schema + "\".stock_levels (product_id, store_id, quantity) "
                    + "VALUES (?, ?, 100)", productId, storeId);
        }
        token = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        year = String.valueOf(LocalDate.now(ZoneId.of("Africa/Douala")).getYear());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private void moduleRow(String moduleId, String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, ?, ?, 'FREE') "
                + "ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status", tenantId, moduleId,
                status);
        registry.evict(schema);
    }

    private void plan(String planType, String expiresInterval) {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = '" + planType + "', expires_at = "
                + (expiresInterval == null ? "NULL" : "now() + interval '" + expiresInterval + "'"));
        registry.evict(schema);
    }

    private void dropInvoicingTables() {
        jdbc.execute("DROP TABLE IF EXISTS \"" + schema + "\".invoice_items, \"" + schema + "\".invoices, \""
                + schema + "\".invoice_counters, \"" + schema + "\".invoicing_settings, \"" + schema
                + "\".flyway_history_invoicing CASCADE");
    }

    private List<String> tables() {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = ANY (?) ORDER BY 1", String.class, schema, TABLES.toArray(new String[0]));
    }

    private MvcResult call(HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(expectedStatus);
        return objectMapper.readTree(body(r)).path("data");
    }

    /** A sale of {@code quantity} × 1 000 F through {@code POST /api/v1/sales}. */
    private UUID sell(int quantity, int discount, UUID clientId, String paymentMode, String status) throws Exception {
        UUID saleId = UUID.randomUUID();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("productName", "Savon 14.4");
        item.put("catalogueUnitPrice", 1000);
        item.put("appliedUnitPrice", 1000);
        item.put("quantity", quantity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleId", saleId);
        body.put("paymentMode", paymentMode);
        body.put("clientId", clientId);
        body.put("storeId", storeId);
        body.put("discountAmount", discount);
        if (paymentMode.equals("MOBILE_MONEY")) {
            body.put("mobileMoneyRef", "MP2610301234");
        }
        if (status != null) {
            body.put("status", status);
        }
        body.put("items", List.of(item));
        MvcResult r = call(HttpMethod.POST, "/api/v1/sales", body);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        return saleId;
    }

    private int invoiceCount() {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".invoices", Integer.class);
    }

    private long counter() {
        List<Long> v = jdbc.queryForList("SELECT last_number FROM \"" + schema + "\".invoice_counters", Long.class);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private List<Map<String, Object>> invoicesOf(UUID saleId) {
        return jdbc.queryForList("SELECT id, number, status, total_ttc, client_id, client_name, client_phone, "
                + "payment_method, created_by, prices_include_vat FROM \"" + schema
                + "\".invoices WHERE source_sale_id = ?", saleId);
    }

    private Map<String, Object> awaitInvoice(UUID saleId) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(invoicesOf(saleId)).hasSize(1));
        return invoicesOf(saleId).get(0);
    }

    /** Waits until every publication of the bridge for the sale is completed (delivered or skipped). */
    private void awaitBridgeCompleted(UUID saleId) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT completion_date FROM public.event_publication WHERE listener_id LIKE ? "
                        + "AND serialized_event LIKE ?", "%SaleInvoicingListener%", "%" + saleId + "%"))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNotNull()));
    }

    private SaleCompletedEvent event(UUID saleId, UUID clientId, List<SaleCompletedEvent.Line> lines, int total) {
        return new SaleCompletedEvent(saleId, ownerId, schema, storeId, total, 0, "[]", Instant.now(), clientId,
                "CASH", lines);
    }

    /** The listener without its proxy (no async, no tenant binding): called synchronously with the tenant bound. */
    private void deliverDirectly(SaleCompletedEvent event) {
        SaleInvoicingListener target = AopTestUtils.getUltimateTargetObject(listener);
        TenantContext.setCurrentTenant(schema);
        try {
            target.onSaleCompleted(event);
        } finally {
            TenantContext.clear();
        }
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sale recorded, invoicing ENABLED → one PAID invoice: number, counter client, « Espèces », TTC, "
            + "saleId, audit under the seller")
    void saleInvoiced() throws Exception {
        UUID saleId = sell(2, 0, null, "CASH", null);

        Map<String, Object> inv = awaitInvoice(saleId);
        assertThat(inv.get("status")).isEqualTo("PAID");
        assertThat((String) inv.get("number")).isEqualTo("FAC-" + year + "-000001");
        assertThat(inv.get("client_name")).isEqualTo("Client comptoir");
        assertThat(inv.get("client_id")).isNull();
        assertThat(inv.get("payment_method")).isEqualTo("Espèces");
        assertThat(((Number) inv.get("total_ttc")).longValue()).isEqualTo(2000);
        assertThat(inv.get("created_by")).isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("SELECT description FROM \"" + schema + "\".invoice_items WHERE invoice_id = ?",
                String.class, inv.get("id"))).isEqualTo("Savon 14.4");

        JsonNode dto = data(call(HttpMethod.GET, "/api/v1/invoices/" + inv.get("id"), null), 200);
        assertThat(dto.path("saleId").asText()).isEqualTo(saleId.toString());
        assertThat(dto.path("status").asText()).isEqualTo("PAID");
        assertThat(dto.path("totalTtc").asLong()).isEqualTo(2000);

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList("SELECT action FROM \"" + schema
                        + "\".audit_log WHERE entity_type = 'Invoice' AND entity_id = ? AND user_id = ?",
                String.class, inv.get("id"), ownerId))
                .containsExactlyInAnyOrder("INVOICE_CREATED", "INVOICE_ISSUED", "INVOICE_PAID"));

        // a manual invoice has no saleId
        JsonNode manual = data(call(HttpMethod.POST, "/api/v1/invoices", Map.of("client", Map.of("name", "Awa"),
                "lines", List.of(Map.of("description", "Riz", "quantity", 1, "unitPrice", 500)))), 201);
        assertThat(manual.path("saleId").isNull()).isTrue();
    }

    @Test
    @DisplayName("discount not divisible, Mobile Money: « Savon 14.4 (× 3) » 1 × 2 900, TTC = sale total")
    void discountedSale() throws Exception {
        UUID saleId = sell(3, 100, null, "MOBILE_MONEY", null);

        Map<String, Object> inv = awaitInvoice(saleId);
        assertThat(((Number) inv.get("total_ttc")).longValue()).isEqualTo(2900);
        assertThat(inv.get("payment_method")).isEqualTo("Mobile Money");
        Map<String, Object> line = jdbc.queryForMap("SELECT description, quantity, unit_price FROM \"" + schema
                + "\".invoice_items WHERE invoice_id = ?", inv.get("id"));
        assertThat(line.get("description")).isEqualTo("Savon 14.4 (× 3)");
        assertThat(((Number) line.get("quantity")).intValue()).isEqualTo(1);
        assertThat(((Number) line.get("unit_price")).longValue()).isEqualTo(2900);
    }

    @Test
    @DisplayName("VAT-registered seller with prices excluding VAT: the invoice still treats sale prices as TTC")
    void vatRegisteredSeller() throws Exception {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("legalName", "Boutique Awa SARL");
        s.put("vatRegistered", true);
        s.put("vatRate", 19.25);
        s.put("pricesIncludeVat", false);
        data(call(HttpMethod.PUT, "/api/v1/invoicing/settings", s), 200);

        UUID saleId = sell(2, 0, null, "CASH", null);

        Map<String, Object> inv = awaitInvoice(saleId);
        assertThat(((Number) inv.get("total_ttc")).longValue()).isEqualTo(2000);
        assertThat(inv.get("prices_include_vat")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT total_vat FROM \"" + schema + "\".invoices WHERE id = ?", Long.class,
                inv.get("id"))).isEqualTo(2000 - Math.round(2000 / 1.1925));
    }

    @Test
    @DisplayName("CRM client on the sale → invoice client name, phone and id from the CRM")
    void crmClient() throws Exception {
        UUID clientId = jdbc.queryForObject("INSERT INTO \"" + schema + "\".clients (name, phone) VALUES "
                + "('Awa Ngono', '+237690000044') RETURNING id", UUID.class);

        Map<String, Object> inv = awaitInvoice(sell(1, 0, clientId, "CASH", null));

        assertThat(inv.get("client_id")).isEqualTo(clientId);
        assertThat(inv.get("client_name")).isEqualTo("Awa Ngono");
        assertThat(inv.get("client_phone")).isEqualTo("+237690000044");
    }

    @Test
    @DisplayName("client on the event, crm DISABLED → « Client comptoir »")
    void clientCrmOff() {
        UUID clientId = jdbc.queryForObject("INSERT INTO \"" + schema + "\".clients (name, phone) VALUES "
                + "('Awa Ngono', '+237690000045') RETURNING id", UUID.class);
        moduleRow("crm", "DISABLED");
        UUID saleId = UUID.randomUUID();

        deliverDirectly(event(saleId, clientId, List.of(new SaleCompletedEvent.Line("Riz", 1500, 1)), 1500));

        Map<String, Object> inv = invoicesOf(saleId).get(0);
        assertThat(inv.get("client_name")).isEqualTo("Client comptoir");
        assertThat(inv.get("client_id")).isNull();
    }

    @Test
    @DisplayName("replay: the same sale.completed published again → still one invoice, counter +1 only")
    void replaySequential() throws Exception {
        UUID saleId = sell(1, 0, null, "CASH", null);
        awaitInvoice(saleId);
        awaitBridgeCompleted(saleId);
        assertThat(counter()).isEqualTo(1);

        SaleCompletedEvent again = event(saleId, null, List.of(new SaleCompletedEvent.Line("Savon 14.4", 1000, 1)),
                1000);
        tx.executeWithoutResult(s -> publisher.publishEvent(again)); // republish through the registry
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.event_publication WHERE listener_id LIKE ? AND serialized_event LIKE ? "
                        + "AND completion_date IS NOT NULL", Integer.class, "%SaleInvoicingListener%",
                "%" + saleId + "%")).isEqualTo(2));
        deliverDirectly(again);

        assertThat(invoicesOf(saleId)).hasSize(1);
        assertThat(invoiceCount()).isEqualTo(1);
        assertThat(counter()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent replay: 6 deliveries of the same sale at once → one invoice, counter +1 only")
    void replayConcurrent() throws Exception {
        UUID saleId = UUID.randomUUID();
        SaleCompletedEvent e = event(saleId, null, List.of(new SaleCompletedEvent.Line("Riz", 1000, 2)), 2000);
        deliverDirectly(event(UUID.randomUUID(), null, List.of(new SaleCompletedEvent.Line("Sel", 100, 1)), 100));
        assertThat(counter()).isEqualTo(1);

        int n = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    deliverDirectly(e);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // no delivery fails
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(invoicesOf(saleId)).hasSize(1);
        assertThat(invoicesOf(saleId).get(0).get("number")).isEqualTo("FAC-" + year + "-000002");
        assertThat(counter()).isEqualTo(2);
    }

    @Test
    @DisplayName("invoicing DISABLED → no invoice and no invoicing table created")
    void invoicingDisabled() throws Exception {
        dropInvoicingTables();
        moduleRow("invoicing", "DISABLED");

        UUID saleId = sell(1, 0, null, "CASH", null);
        awaitBridgeCompleted(saleId);

        assertThat(tables()).isEmpty();
    }

    @Test
    @DisplayName("FREE tenant → no invoice and no invoicing table created; manual invoicing still answers 403")
    void freeTenant() throws Exception {
        dropInvoicingTables();
        plan("FREE", null);

        UUID saleId = sell(1, 0, null, "CASH", null);
        awaitBridgeCompleted(saleId);

        assertThat(tables()).isEmpty();
        MvcResult r = call(HttpMethod.GET, "/api/v1/invoices", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r)).contains("MODULE_NOT_ENTITLED");
    }

    @Test
    @DisplayName("invoicing READ_ONLY (grace) → no invoice")
    void invoicingReadOnly() throws Exception {
        plan("PREMIUM_TRIAL", "-1 day");

        UUID saleId = sell(1, 0, null, "CASH", null);
        awaitBridgeCompleted(saleId);

        assertThat(invoiceCount()).isZero();
    }

    @Test
    @DisplayName("pos DISABLED, invoicing enabled: a (stale / retried) sale.completed → no invoice; manual invoicing "
            + "still answers")
    void posDisabled() throws Exception {
        moduleRow("pos", "DISABLED");
        UUID saleId = UUID.randomUUID();

        deliverDirectly(event(saleId, null, List.of(new SaleCompletedEvent.Line("Riz", 1500, 1)), 1500));

        assertThat(invoicesOf(saleId)).isEmpty();
        assertThat(call(HttpMethod.GET, "/api/v1/invoices", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("event published before 14.4 (no lines) → no invoice")
    void legacyEventWithoutLines() {
        UUID saleId = UUID.randomUUID();
        deliverDirectly(event(saleId, null, null, 1000));
        assertThat(invoicesOf(saleId)).isEmpty();
    }

    @Test
    @DisplayName("pending sale: no invoice while pending; invoiced when validated")
    void pendingSaleValidated() throws Exception {
        UUID saleId = sell(2, 0, null, "CASH", "PENDING_VALIDATION");
        assertThat(invoicesOf(saleId)).isEmpty();

        data(call(HttpMethod.POST, "/api/v1/sales/" + saleId + "/validate",
                Map.of("justification", "Client habituel, vente vérifiée en boutique")), 200);

        Map<String, Object> inv = awaitInvoice(saleId);
        assertThat(inv.get("status")).isEqualTo("PAID");
        assertThat(((Number) inv.get("total_ttc")).longValue()).isEqualTo(2000);
    }
}
