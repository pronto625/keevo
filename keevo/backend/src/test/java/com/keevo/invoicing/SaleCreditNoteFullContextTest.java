package com.keevo.invoicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.invoicing.invoice.adapter.in.event.SaleCreditNoteListener;
import com.keevo.invoicing.invoice.adapter.in.event.SaleInvoicingListener;
import com.keevo.shared.event.catalog.sale.SaleCancelledEvent;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import com.keevo.shared.event.catalog.sale.SaleCorrectedEvent;
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
 * Story 14.7 — the accounting documents follow a cancelled or corrected sale that was invoiced automatically (POS →
 * Invoicing bridge), on the real chain: a sale through {@code POST /api/v1/sales}, its automatic invoice, then
 * {@code POST /api/v1/sales/{id}/cancel} or {@code /correct}; replays never create a second document.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Sale cancel / correction → credit note — full context (Story 14.7)")
class SaleCreditNoteFullContextTest extends FullContextIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreateProductUseCase createProduct;
    @Autowired ModuleRegistry registry;
    @Autowired SaleCreditNoteListener listener;
    @Autowired SaleInvoicingListener invoicingListener;
    @Autowired ApplicationEventPublisher publisher;

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
            productId = createProduct.execute(new CreateProductDto("Savon 14.7", null, null, null, 1000, null,
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

    /** A cash sale of {@code quantity} × 1 000 F through {@code POST /api/v1/sales}. */
    private UUID sell(int quantity) throws Exception {
        UUID saleId = UUID.randomUUID();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("productName", "Savon 14.7");
        item.put("catalogueUnitPrice", 1000);
        item.put("appliedUnitPrice", 1000);
        item.put("quantity", quantity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleId", saleId);
        body.put("paymentMode", "CASH");
        body.put("storeId", storeId);
        body.put("discountAmount", 0);
        body.put("items", List.of(item));
        MvcResult r = call(HttpMethod.POST, "/api/v1/sales", body);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        return saleId;
    }

    private List<Map<String, Object>> invoicesOf(UUID saleId) {
        return jdbc.queryForList("SELECT id, number, status, total_ttc, superseded_at FROM \"" + schema
                + "\".invoices WHERE source_sale_id = ? ORDER BY created_at, number", saleId);
    }

    private Map<String, Object> awaitInvoice(UUID saleId) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(invoicesOf(saleId)).hasSize(1));
        return invoicesOf(saleId).get(0);
    }

    private List<Map<String, Object>> creditNotesOf(Object invoiceId) {
        return jdbc.queryForList("SELECT id, number, reason, total_ttc, created_by, source_event FROM \"" + schema
                + "\".credit_notes WHERE invoice_id = ? ORDER BY number", invoiceId);
    }

    private int creditNoteCount() {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".credit_notes", Integer.class);
    }

    /** Waits until every publication of the credit-note bridge for the sale is completed. */
    private void awaitBridgeCompleted(UUID saleId) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT completion_date FROM public.event_publication WHERE listener_id LIKE ? "
                        + "AND serialized_event LIKE ?", "%SaleCreditNoteListener%", "%" + saleId + "%"))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNotNull()));
    }

    private void cancel(UUID saleId) throws Exception {
        data(call(HttpMethod.POST, "/api/v1/sales/" + saleId + "/cancel",
                Map.of("justification", "Le client a rapporté l'article")), 200);
    }

    private void correct(UUID saleId, int quantity) throws Exception {
        UUID itemId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".sale_items WHERE sale_id = ?",
                UUID.class, saleId);
        data(call(HttpMethod.POST, "/api/v1/sales/" + saleId + "/correct", Map.of(
                "justification", "Erreur de quantité à la caisse", "itemQuantities", Map.of(itemId, quantity))), 200);
    }

    private void deliverDirectly(Runnable delivery) {
        TenantContext.setCurrentTenant(schema);
        try {
            delivery.run();
        } finally {
            TenantContext.clear();
        }
    }

    private SaleCreditNoteListener target() {
        return AopTestUtils.getUltimateTargetObject(listener);
    }

    private SaleCorrectedEvent correctedEvent(UUID saleId, Instant at, int quantity) {
        return new SaleCorrectedEvent(saleId, ownerId, "Erreur de quantité", "[]", "[]", schema, at, storeId,
                List.of(new SaleCompletedEvent.Line("Savon 14.7", 1000, quantity)), (long) quantity * 1000, null,
                "CASH");
    }

    // ── matrix ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("auto-invoiced sale cancelled → 1 full credit note « Annulation de la vente » under the seller; "
            + "replay → still 1")
    void saleCancelled() throws Exception {
        UUID saleId = sell(2);
        Map<String, Object> inv = awaitInvoice(saleId);

        cancel(saleId);

        await().atMost(WAIT).untilAsserted(() -> assertThat(creditNotesOf(inv.get("id"))).hasSize(1));
        Map<String, Object> note = creditNotesOf(inv.get("id")).get(0);
        assertThat(note.get("number")).isEqualTo("AV-" + year + "-000001");
        assertThat(note.get("reason")).isEqualTo("Annulation de la vente");
        assertThat(((Number) note.get("total_ttc")).longValue()).isEqualTo(2000);
        assertThat(note.get("created_by")).isEqualTo(ownerId);
        assertThat(note.get("source_event")).isEqualTo("cancel:" + saleId);
        awaitBridgeCompleted(saleId);

        JsonNode dto = data(call(HttpMethod.GET, "/api/v1/invoices/" + inv.get("id"), null), 200);
        assertThat(dto.path("status").asText()).isEqualTo("PAID");
        assertThat(dto.path("number").asText()).isEqualTo(inv.get("number"));
        assertThat(dto.path("fullyCredited").asBoolean()).isTrue();

        // replay (restart republish / resubmission): nothing more
        deliverDirectly(() -> target().onSaleCancelled(new SaleCancelledEvent(saleId, ownerId, "rejeu", "[]",
                schema, Instant.now(), storeId)));
        assertThat(creditNoteCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_value FROM \"" + schema + "\".credit_note_counters",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("auto-invoiced sale corrected 2 → 3: credit note on the old invoice, old superseded, new PAID "
            + "invoice of 3 000, ?saleId= returns the new one; replay → no extra document")
    void saleCorrected() throws Exception {
        UUID saleId = sell(2);
        Map<String, Object> old = awaitInvoice(saleId);

        correct(saleId, 3);

        await().atMost(WAIT).untilAsserted(() -> assertThat(invoicesOf(saleId)).hasSize(2));
        awaitBridgeCompleted(saleId);
        List<Map<String, Object>> invoices = invoicesOf(saleId);
        Map<String, Object> oldNow = invoices.stream().filter(i -> i.get("id").equals(old.get("id"))).findFirst()
                .orElseThrow();
        Map<String, Object> fresh = invoices.stream().filter(i -> !i.get("id").equals(old.get("id"))).findFirst()
                .orElseThrow();
        assertThat(oldNow.get("superseded_at")).isNotNull();
        assertThat(oldNow.get("status")).isEqualTo("PAID");
        assertThat(oldNow.get("number")).isEqualTo(old.get("number"));
        assertThat(((Number) oldNow.get("total_ttc")).longValue()).isEqualTo(2000);
        assertThat(fresh.get("superseded_at")).isNull();
        assertThat(fresh.get("status")).isEqualTo("PAID");
        assertThat(fresh.get("number")).isEqualTo("FAC-" + year + "-000002");
        assertThat(((Number) fresh.get("total_ttc")).longValue()).isEqualTo(3000);

        List<Map<String, Object>> notes = creditNotesOf(old.get("id"));
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("reason")).isEqualTo("Correction de la vente");
        assertThat(((Number) notes.get(0).get("total_ttc")).longValue()).isEqualTo(2000);
        String key = (String) notes.get(0).get("source_event");
        assertThat(key).startsWith("correct:" + saleId + ":");
        assertThat(creditNotesOf(fresh.get("id"))).isEmpty();

        JsonNode bySale = data(call(HttpMethod.GET, "/api/v1/invoices?saleId=" + saleId, null), 200);
        assertThat(bySale).hasSize(1);
        assertThat(bySale.get(0).path("id").asText()).isEqualTo(fresh.get("id").toString());
        assertThat(bySale.get(0).path("totalTtc").asLong()).isEqualTo(3000);

        // replay of the same correction event (same occurredAt) → nothing more
        Instant at = Instant.ofEpochMilli(Long.parseLong(key.substring(key.lastIndexOf(':') + 1)));
        deliverDirectly(() -> target().onSaleCorrected(correctedEvent(saleId, at, 3)));
        assertThat(invoicesOf(saleId)).hasSize(2);
        assertThat(creditNoteCount()).isEqualTo(1);

        // the corrected sale cancelled afterwards → the new invoice is credited
        cancel(saleId);
        await().atMost(WAIT).untilAsserted(() -> assertThat(creditNotesOf(fresh.get("id"))).hasSize(1));
        assertThat(((Number) creditNotesOf(fresh.get("id")).get(0).get("total_ttc")).longValue()).isEqualTo(3000);
    }

    @Test
    @DisplayName("concurrent replays of one cancellation and of one correction → exactly one document each")
    void concurrentReplays() throws Exception {
        UUID saleId = sell(1);
        Map<String, Object> inv = awaitInvoice(saleId);
        Instant at = Instant.parse("2026-10-30T12:00:00.123Z");

        runConcurrently(6, () -> target().onSaleCorrected(correctedEvent(saleId, at, 2)));
        assertThat(invoicesOf(saleId)).hasSize(2);
        assertThat(creditNotesOf(inv.get("id"))).hasSize(1);

        SaleCancelledEvent cancelled = new SaleCancelledEvent(saleId, ownerId, "annulation", "[]", schema,
                Instant.now(), storeId);
        runConcurrently(6, () -> target().onSaleCancelled(cancelled));
        assertThat(creditNoteCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_value FROM \"" + schema + "\".credit_note_counters",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT last_number FROM \"" + schema + "\".invoice_counters",
                Integer.class)).isEqualTo(2);
    }

    private void runConcurrently(int n, Runnable delivery) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    deliverDirectly(delivery);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("sale never invoiced (invoicing DISABLED when sold) then cancelled → nothing")
    void neverInvoiced() throws Exception {
        moduleRow("invoicing", "DISABLED");
        UUID saleId = sell(1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForList(
                "SELECT completion_date FROM public.event_publication WHERE listener_id LIKE ? "
                        + "AND serialized_event LIKE ?", "%SaleInvoicingListener%", "%" + saleId + "%"))
                .allSatisfy(row -> assertThat(row.get("completion_date")).isNotNull()));
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ? AND module_id = 'invoicing'", tenantId);
        registry.evict(schema); // back to the trial: invoicing ENABLED
        data(call(HttpMethod.GET, "/api/v1/invoices", null), 200); // provisions the tables

        cancel(saleId);
        awaitBridgeCompleted(saleId);

        assertThat(invoicesOf(saleId)).isEmpty();
        assertThat(creditNoteCount()).isZero();
    }

    @Test
    @DisplayName("pos DISABLED: a stale cancellation event creates nothing")
    void posDisabled() throws Exception {
        UUID saleId = sell(1);
        awaitInvoice(saleId);
        moduleRow("pos", "DISABLED");

        deliverDirectly(() -> target().onSaleCancelled(new SaleCancelledEvent(saleId, ownerId, "annulation", "[]",
                schema, Instant.now(), storeId)));

        assertThat(creditNoteCount()).isZero();
    }

    @Test
    @DisplayName("correction event published before 14.7 (no lines) → nothing")
    void legacyCorrectionEvent() throws Exception {
        UUID saleId = sell(1);
        awaitInvoice(saleId);

        deliverDirectly(() -> target().onSaleCorrected(new SaleCorrectedEvent(saleId, ownerId, "ancienne", "[]",
                "[]", schema, Instant.now(), storeId)));

        assertThat(invoicesOf(saleId)).hasSize(1);
        assertThat(creditNoteCount()).isZero();
    }

    // ── events delivered out of order (offline sync batch, pool threads) ─────────────────────────────────

    private SaleCompletedEvent completedEvent(UUID saleId, int quantity) {
        return new SaleCompletedEvent(saleId, ownerId, schema, storeId, quantity * 1000, 0, "[]", Instant.now(), null,
                "CASH", List.of(new SaleCompletedEvent.Line("Savon 14.7", 1000, quantity)));
    }

    private void deliverCompleted(SaleCompletedEvent event) {
        deliverDirectly(() -> AopTestUtils.<SaleInvoicingListener>getUltimateTargetObject(invoicingListener)
                .onSaleCompleted(event));
    }

    @Test
    @DisplayName("cancel delivered before sale.completed → the late completion issues no invoice")
    void cancelBeforeCompleted() throws Exception {
        UUID saleId = UUID.randomUUID();
        data(call(HttpMethod.GET, "/api/v1/invoices", null), 200); // provisions the tables

        deliverDirectly(() -> target().onSaleCancelled(new SaleCancelledEvent(saleId, ownerId, "annulation", "[]",
                schema, Instant.now(), storeId)));
        deliverCompleted(completedEvent(saleId, 2));

        assertThat(invoicesOf(saleId)).isEmpty();
        assertThat(creditNoteCount()).isZero();
    }

    @Test
    @DisplayName("correction delivered before sale.completed → one invoice with the corrected total; the late "
            + "completion does nothing")
    void correctBeforeCompleted() throws Exception {
        UUID saleId = UUID.randomUUID();
        data(call(HttpMethod.GET, "/api/v1/invoices", null), 200);

        deliverDirectly(() -> target().onSaleCorrected(correctedEvent(saleId, Instant.now(), 3)));
        deliverCompleted(completedEvent(saleId, 2));

        List<Map<String, Object>> invoices = invoicesOf(saleId);
        assertThat(invoices).hasSize(1);
        assertThat(((Number) invoices.get(0).get("total_ttc")).longValue()).isEqualTo(3000);
        assertThat(invoices.get(0).get("status")).isEqualTo("PAID");
        assertThat(creditNoteCount()).isZero();
    }

    @Test
    @DisplayName("an older correction delivered after a newer one → no change")
    void olderCorrectionAfterNewer() throws Exception {
        UUID saleId = sell(1);
        awaitInvoice(saleId);
        Instant t1 = Instant.parse("2026-10-30T12:00:00Z");

        deliverDirectly(() -> target().onSaleCorrected(correctedEvent(saleId, t1.plusSeconds(60), 3)));
        deliverDirectly(() -> target().onSaleCorrected(correctedEvent(saleId, t1, 5)));

        List<Map<String, Object>> invoices = invoicesOf(saleId);
        assertThat(invoices).hasSize(2);
        assertThat(invoices).filteredOn(i -> i.get("superseded_at") == null)
                .extracting(i -> ((Number) i.get("total_ttc")).longValue()).containsExactly(3000L);
        assertThat(creditNoteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("correction of a sale whose invoice was fully credited by hand → superseded without a new credit "
            + "note, new invoice issued")
    void correctionAfterManualFullCredit() throws Exception {
        UUID saleId = sell(2);
        Map<String, Object> old = awaitInvoice(saleId);
        data(call(HttpMethod.POST, "/api/v1/invoices/" + old.get("id") + "/credit-notes",
                Map.of("reason", "Geste commercial")), 201);

        deliverDirectly(() -> target().onSaleCorrected(correctedEvent(saleId, Instant.now(), 3)));

        List<Map<String, Object>> invoices = invoicesOf(saleId);
        assertThat(invoices).hasSize(2);
        assertThat(invoices).filteredOn(i -> i.get("id").equals(old.get("id")))
                .allSatisfy(i -> assertThat(i.get("superseded_at")).isNotNull());
        assertThat(invoices).filteredOn(i -> i.get("superseded_at") == null)
                .extracting(i -> ((Number) i.get("total_ttc")).longValue()).containsExactly(3000L);
        assertThat(creditNoteCount()).isEqualTo(1);
        JsonNode dto = data(call(HttpMethod.GET, "/api/v1/invoices/" + old.get("id"), null), 200);
        assertThat(dto.path("supersededAt").isNull()).isFalse();
        assertThat(dto.path("fullyCredited").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("corrected content refused by the invoice domain → everything rolled back (no credit note, not "
            + "superseded, counter unchanged), publication completed")
    void correctionRefusedRollsBack() throws Exception {
        UUID saleId = sell(1);
        Map<String, Object> inv = awaitInvoice(saleId);
        long huge = Long.MAX_VALUE / 2 + 1; // accepted by the mapper, refused by the VAT computation
        SaleCorrectedEvent event = new SaleCorrectedEvent(saleId, ownerId, "Erreur de prix", "[]", "[]", schema,
                Instant.now(), storeId, List.of(new SaleCompletedEvent.Line("Lingot", huge, 1)), huge, null, "CASH");

        TenantContext.setCurrentTenant(schema);
        try {
            tx.executeWithoutResult(st -> publisher.publishEvent(event));
        } finally {
            TenantContext.clear();
        }
        awaitBridgeCompleted(saleId);

        assertThat(creditNoteCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".credit_note_counters",
                Integer.class)).isZero();
        assertThat(invoicesOf(saleId)).hasSize(1);
        assertThat(invoicesOf(saleId).get(0).get("id")).isEqualTo(inv.get("id"));
        assertThat(invoicesOf(saleId).get(0).get("superseded_at")).isNull();
    }
}
