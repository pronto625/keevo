package com.keevo.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.event.catalog.DomainEvent;
import com.keevo.shared.event.catalog.invoicing.InvoiceCreatedEvent;
import com.keevo.shared.event.catalog.invoicing.InvoiceIssuedEvent;
import com.keevo.shared.event.catalog.invoicing.InvoicePaidEvent;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.module.ModuleLifecycle;
import com.keevo.shared.module.ModuleLifecycle.ActivationRequest;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * Story 14.6 (MOD-FR7/8/9/10) — acceptance test of the « connected mini-apps » vision. One Premium tenant goes
 * through the four module-activation combinations <b>in order</b>, its modules toggled through the real 13.8
 * lifecycle ({@link ModuleLifecycle}, the super-admin path), and every outcome asserted at the REST surface the app
 * uses, plus the published events:
 * <ol>
 *   <li>Facturation seule (pos, crm off): manual invoice numbered and paid, free-text client, no POS, empty slot;</li>
 *   <li>+ CRM: a CRM client is suggested and linked / prefilled on a draft;</li>
 *   <li>+ Point de vente: a sale gets exactly one automatic PAID invoice (CRM client, TTC = sale total), the slot
 *       lists « Facturer », {@code ?saleId=} finds it, and the created / issued / paid events are published;</li>
 *   <li>Point de vente désactivé: no sale, no automatic invoice on redelivery, manual invoicing still works, every
 *       earlier invoice is kept unchanged, empty slot.</li>
 * </ol>
 *
 * <p><b>Events.</b> Spring Modulith's {@code PublishedEventsExtension} binds its listener to the test thread
 * (an {@code InheritableThreadLocal}), so it cannot see the invoice events of the automatic invoice, published on the
 * registry's async executor thread. This class therefore records every {@link DomainEvent} of its tenant with a
 * context-wide listener, and asserts on them through Modulith's {@link PublishedEvents} API.
 *
 * <p>Each scenario only runs when the previous one passed (the four share the tenant): a regression fails the named
 * scenario, the following ones are reported as aborted.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Vision modulaire — test d'acceptation, 4 scénarios d'activation (Story 14.6)")
class ModularVisionAcceptanceTest extends FullContextIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String SLOT_URL = "/api/v1/tenants/me/extensions/pos.checkout.post-sale-actions";
    private static final String NOT_ENTITLED_FR = "Ce module n'est pas inclus dans votre abonnement";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModuleRegistry registry;
    @Autowired ModuleLifecycle lifecycle;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ConfigurableApplicationContext context;

    private final UUID ownerId = UUID.randomUUID();
    private final ModuleLifecycle.Actor superAdmin = new ModuleLifecycle.Actor(UUID.randomUUID(), true);
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final ApplicationListener<ApplicationEvent> recorder = e -> {
        if (e instanceof PayloadApplicationEvent<?> p && p.getPayload() instanceof DomainEvent d && this.schema != null && this.schema.equals(d.tenantId())) {
            events.add(d);
        }
    };

    private String schema;
    private UUID tenantId;
    private UUID storeId;
    private String token;
    private String year;
    private int passed;

    // shared across the scenarios
    private String manualInvoiceId;
    private UUID crmClientId;
    private UUID saleId;
    private String autoInvoiceId;
    private SaleCompletedEvent saleEvent;

    @BeforeAll
    void createPremiumTenant() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        // a paying Premium tenant (registration starts on a trial): invoicing is allowed by its plan
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'PREMIUM', status = 'ACTIVE', "
                + "expires_at = now() + interval '1 year'");
        registry.evict(schema);
        token = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        year = String.valueOf(LocalDate.now(ZoneId.of("Africa/Douala")).getYear());
        context.addApplicationListener(recorder);
    }

    @AfterAll
    void cleanUp() {
        context.removeApplicationListener(recorder);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── scenario 1 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Scénario 1 — Facturation seule : facture manuelle numérotée et payée, client libre, pas de vente, "
            + "emplacement vide")
    void scenario1_invoicingAlone() throws Exception {
        String sc = "Scénario 1 (Facturation seule)";
        // pos first (nothing else requires it), then crm, then catalog / inventory as far as the rules allow
        lifecycle.deactivate(tenantId, "pos", superAdmin);
        lifecycle.deactivate(tenantId, "crm", superAdmin);
        String kept = deactivateIfAllowed("inventory") + deactivateIfAllowed("catalog");
        String alone = sc + " — Facturation seule (restés actifs à cause de modules dépendants : "
                + (kept.isEmpty() ? "aucun" : kept.trim()) + ")";
        assertState(alone, "invoicing", ModuleState.ENABLED);
        assertState(alone, "pos", ModuleState.DISABLED);
        assertState(alone, "crm", ModuleState.DISABLED);

        JsonNode draft = data(sc + " — création du brouillon", call(HttpMethod.POST, "/api/v1/invoices",
                invoiceBody(null, "Awa Ngono", "Sac de riz 25 kg", 2, 12_500)), 201);
        manualInvoiceId = draft.path("id").asText();
        assertThat(draft.path("status").asText()).as(sc).isEqualTo("DRAFT");
        assertThat(draft.path("client").path("id").isNull()).as(sc + " — client en texte libre").isTrue();
        assertThat(draft.path("client").path("name").asText()).as(sc).isEqualTo("Awa Ngono");

        JsonNode issued = data(sc + " — émission", call(HttpMethod.POST,
                "/api/v1/invoices/" + manualInvoiceId + "/issue", null), 200);
        assertThat(issued.path("number").asText()).as(sc + " — numéro").isEqualTo("FAC-" + year + "-000001");
        JsonNode paid = data(sc + " — paiement", call(HttpMethod.POST, "/api/v1/invoices/" + manualInvoiceId + "/pay",
                Map.of("paymentMethod", "Espèces")), 200);
        assertThat(paid.path("status").asText()).as(sc).isEqualTo("PAID");
        assertThat(paid.path("totalTtc").asLong()).as(sc).isEqualTo(25_000);
        assertThat(paid.path("saleId").isNull()).as(sc + " — facture manuelle sans vente").isTrue();

        JsonNode clients = data(sc + " — sélecteur client", call(HttpMethod.GET, "/api/v1/invoices/clients", null),
                200);
        assertThat(clients.path("crmAvailable").asBoolean()).as(sc + " — crmAvailable").isFalse();

        MvcResult sale = call(HttpMethod.POST, "/api/v1/sales", saleBody(UUID.randomUUID(), UUID.randomUUID(), null,
                1));
        assertNotEntitled(sc + " — POST /sales", sale);

        assertThat(slot(sc)).as(sc + " — emplacement « après vente »").isEmpty();
        passed = 1;
    }

    // ── scenario 2 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Scénario 2 — + CRM : le client CRM est proposé, puis lié et prérempli sur un brouillon")
    void scenario2_plusCrm() throws Exception {
        requirePrevious(1, "Scénario 2 (+ CRM)");
        String sc = "Scénario 2 (+ CRM)";
        lifecycle.activate(tenantId, "crm", ActivationRequest.active(), superAdmin);
        assertState(sc, "crm", ModuleState.ENABLED);

        JsonNode client = data(sc + " — création du client CRM", call(HttpMethod.POST, "/api/v1/clients",
                Map.of("name", "Mireille Ngo", "phone", "+237690000061")), 201);
        crmClientId = UUID.fromString(client.path("id").asText());

        JsonNode suggestions = data(sc + " — suggestions", call(HttpMethod.GET,
                "/api/v1/invoices/clients?search=Mireille", null), 200);
        assertThat(suggestions.path("crmAvailable").asBoolean()).as(sc + " — crmAvailable").isTrue();
        assertThat(suggestions.path("clients").findValuesAsText("id")).as(sc + " — client proposé")
                .containsExactly(crmClientId.toString());

        JsonNode draft = data(sc + " — brouillon lié au client CRM", call(HttpMethod.POST, "/api/v1/invoices",
                invoiceBody(crmClientId, "", "Huile 5 L", 1, 6_000)), 201);
        assertThat(draft.path("client").path("id").asText()).as(sc + " — lien CRM").isEqualTo(crmClientId.toString());
        assertThat(draft.path("client").path("name").asText()).as(sc + " — nom prérempli").isEqualTo("Mireille Ngo");
        assertThat(draft.path("client").path("phone").asText()).as(sc + " — téléphone prérempli")
                .isEqualTo("+237690000061");
        passed = 2;
    }

    // ── scenario 3 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Scénario 3 — + Point de vente : une vente donne une seule facture payée automatique, « Facturer » "
            + "dans l'emplacement, événements publiés")
    void scenario3_plusPos() throws Exception {
        requirePrevious(2, "Scénario 3 (+ Point de vente)");
        String sc = "Scénario 3 (+ Point de vente)";
        lifecycle.activate(tenantId, "pos", ActivationRequest.active(), superAdmin); // with catalog / inventory
        assertState(sc, "pos", ModuleState.ENABLED);
        assertState(sc, "catalog", ModuleState.ENABLED);
        assertState(sc, "inventory", ModuleState.ENABLED);

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "Savon 14.6");
        product.put("price", 1000);
        UUID productId = UUID.fromString(data(sc + " — création du produit", call(HttpMethod.POST, "/api/v1/products",
                product), 201).path("id").asText());
        data(sc + " — entrée de stock", call(HttpMethod.POST, "/api/v1/products/" + productId + "/stock/entry",
                Map.of("storeId", storeId, "quantity", 100, "notes", "Réception")), 201);

        saleId = UUID.randomUUID();
        MvcResult sale = call(HttpMethod.POST, "/api/v1/sales", saleBody(saleId, productId, crmClientId, 2));
        assertThat(sale.getResponse().getStatus()).as(sc + " — vente : " + body(sale)).isEqualTo(201);

        await().atMost(WAIT).untilAsserted(() -> assertThat(invoicesOfSale(saleId))
                .as(sc + " — facture automatique de la vente").hasSize(1));
        JsonNode auto = invoicesOfSale(saleId).get(0);
        autoInvoiceId = auto.path("id").asText();
        assertThat(auto.path("status").asText()).as(sc + " — statut").isEqualTo("PAID");
        assertThat(auto.path("saleId").asText()).as(sc + " — saleId").isEqualTo(saleId.toString());
        assertThat(auto.path("client").path("id").asText()).as(sc + " — client CRM")
                .isEqualTo(crmClientId.toString());
        assertThat(auto.path("client").path("name").asText()).as(sc + " — nom du client CRM")
                .isEqualTo("Mireille Ngo");
        assertThat(auto.path("totalTtc").asLong()).as(sc + " — TTC = total de la vente").isEqualTo(2000);
        assertThat(auto.path("paymentMethod").asText()).as(sc).isEqualTo("Espèces");
        assertThat(auto.path("number").asText()).as(sc + " — numéro").isEqualTo("FAC-" + year + "-000002");

        assertThat(slot(sc)).as(sc + " — emplacement « après vente »").hasSize(1);
        JsonNode action = slot(sc).get(0);
        assertThat(action.path("label").asText()).as(sc).isEqualTo("Facturer");
        assertThat(action.path("action").asText()).as(sc).isEqualTo("invoicing.open-sale-invoice");

        UUID autoId = UUID.fromString(autoInvoiceId);
        await().atMost(WAIT).untilAsserted(() -> {
            PublishedEvents published = PublishedEvents.of(events);
            assertThat(published.ofType(InvoiceCreatedEvent.class).matching(InvoiceCreatedEvent::invoiceId, autoId))
                    .as(sc + " — invoice.created").hasSize(1);
            assertThat(published.ofType(InvoiceIssuedEvent.class).matching(InvoiceIssuedEvent::invoiceId, autoId))
                    .as(sc + " — invoice.issued").hasSize(1);
            assertThat(published.ofType(InvoicePaidEvent.class).matching(InvoicePaidEvent::invoiceId, autoId))
                    .as(sc + " — invoice.paid").hasSize(1);
        });
        var saleEvents = PublishedEvents.of(events).ofType(SaleCompletedEvent.class)
                .matching(SaleCompletedEvent::getSaleId, saleId);
        assertThat(saleEvents).as(sc + " — sale.completed publié").hasSize(1);
        saleEvent = saleEvents.iterator().next();
        awaitBridgeCompleted(saleId, 1);
        passed = 3;
    }

    // ── scenario 4 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Scénario 4 — Point de vente désactivé : plus de vente ni de facture automatique, facturation "
            + "manuelle intacte, factures conservées, emplacement vide")
    void scenario4_posDeactivated() throws Exception {
        requirePrevious(3, "Scénario 4 (Point de vente désactivé)");
        String sc = "Scénario 4 (Point de vente désactivé)";
        Map<String, String> before = snapshot();
        assertThat(before).as(sc + " — factures des scénarios 1 à 3")
                .containsKeys(manualInvoiceId, autoInvoiceId).hasSize(3);

        lifecycle.deactivate(tenantId, "pos", superAdmin);
        assertState(sc, "pos", ModuleState.DISABLED);
        assertState(sc, "invoicing", ModuleState.ENABLED);

        assertNotEntitled(sc + " — POST /sales", call(HttpMethod.POST, "/api/v1/sales",
                saleBody(UUID.randomUUID(), UUID.randomUUID(), null, 1)));

        // redelivery of the scenario-3 sale.completed, and a fresh one for a new sale, through the registry
        UUID freshSaleId = UUID.randomUUID();
        SaleCompletedEvent fresh = new SaleCompletedEvent(freshSaleId, ownerId, schema, storeId, 1500, 0, "[]",
                Instant.now(), null, "CASH", List.of(new SaleCompletedEvent.Line("Riz 1 kg", 1500, 1)));
        long invoiceEventsBefore = invoiceEventCount();
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(saleEvent);
            publisher.publishEvent(fresh);
        });
        awaitBridgeCompleted(saleId, 2);
        awaitBridgeCompleted(freshSaleId, 1);
        assertThat(invoiceEventCount()).as(sc + " — aucun événement facture (créée / émise / payée) après la "
                + "relivraison, ni pour la nouvelle vente ni pour celle du scénario 3").isEqualTo(invoiceEventsBefore);
        UUID autoId = UUID.fromString(autoInvoiceId);
        PublishedEvents published = PublishedEvents.of(events);
        assertThat(published.ofType(InvoiceCreatedEvent.class).matching(InvoiceCreatedEvent::invoiceId, autoId))
                .as(sc + " — toujours un seul invoice.created pour la vente du scénario 3").hasSize(1);
        assertThat(published.ofType(InvoicePaidEvent.class).matching(InvoicePaidEvent::invoiceId, autoId))
                .as(sc + " — toujours un seul invoice.paid pour la vente du scénario 3").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".sales WHERE id = ?", Integer.class,
                saleId)).as(sc + " — la vente du scénario 3 est conservée").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".clients WHERE id = ?", Integer.class,
                crmClientId)).as(sc + " — le client CRM est conservé").isEqualTo(1);
        assertThat(invoicesOfSale(freshSaleId)).as(sc + " — aucune facture pour une nouvelle vente").isEmpty();
        assertThat(invoicesOfSale(saleId)).as(sc + " — toujours une seule facture pour la vente du scénario 3")
                .hasSize(1);
        assertThat(snapshot()).as(sc + " — aucune nouvelle facture après la relivraison").isEqualTo(before);

        JsonNode draft = data(sc + " — création manuelle", call(HttpMethod.POST, "/api/v1/invoices",
                invoiceBody(null, "Paul Etoa", "Ciment 50 kg", 3, 5_000)), 201);
        JsonNode issued = data(sc + " — émission manuelle", call(HttpMethod.POST,
                "/api/v1/invoices/" + draft.path("id").asText() + "/issue", null), 200);
        assertThat(issued.path("status").asText()).as(sc).isEqualTo("ISSUED");
        assertThat(issued.path("number").asText()).as(sc + " — numérotation continue")
                .isEqualTo("FAC-" + year + "-000003");

        Map<String, String> after = snapshot();
        after.remove(draft.path("id").asText());
        assertThat(after).as(sc + " — factures des scénarios 1 à 3 conservées et inchangées").isEqualTo(before);

        assertThat(slot(sc)).as(sc + " — emplacement « après vente »").isEmpty();
        passed = 4;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Deactivates the module unless active modules still require it (the dependency rules decide); returns
     * {@code " <id>"} when it stayed active, {@code ""} otherwise.
     */
    private String deactivateIfAllowed(String moduleId) {
        try {
            lifecycle.deactivate(tenantId, moduleId, superAdmin);
            return "";
        } catch (DomainException e) {
            if (!"MODULE_HAS_DEPENDENTS".equals(e.getDomainCode())) {
                throw e;
            }
            return " " + moduleId;
        }
    }

    /** The scenarios share one tenant: a scenario only makes sense after the previous one passed. */
    private void requirePrevious(int previous, String scenario) {
        assertThat(passed).as(scenario + " — le scénario " + previous + " doit d'abord réussir (lancer la classe "
                + "entière, les scénarios partagent le même commerce)").isEqualTo(previous);
    }

    private long invoiceEventCount() {
        return events.stream().filter(e -> e instanceof InvoiceCreatedEvent || e instanceof InvoiceIssuedEvent
                || e instanceof InvoicePaidEvent).count();
    }

    private void assertState(String scenario, String moduleId, ModuleState expected) {
        registry.evict(schema);
        assertThat(registry.moduleState(schema, moduleId)).as(scenario + " — état du module " + moduleId)
                .isEqualTo(expected);
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

    private JsonNode data(String step, MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(step + " : " + body(r)).isEqualTo(expectedStatus);
        return objectMapper.readTree(body(r)).path("data");
    }

    private static void assertNotEntitled(String step, MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(step + " : " + body(r)).isEqualTo(403);
        assertThat(body(r)).as(step).contains("\"domainCode\":\"MODULE_NOT_ENTITLED\"").contains(NOT_ENTITLED_FR);
    }

    private JsonNode slot(String scenario) throws Exception {
        JsonNode d = data(scenario + " — emplacement « après vente »", call(HttpMethod.GET, SLOT_URL, null), 200);
        assertThat(d.isArray()).as(scenario).isTrue();
        return d;
    }

    private static Map<String, Object> invoiceBody(UUID clientId, String name, String description, int quantity,
                                                   long unitPrice) {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("id", clientId == null ? null : clientId.toString());
        client.put("name", name);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", unitPrice);
        line.put("vatTreatment", "STANDARD");
        return Map.of("client", client, "lines", List.of(line));
    }

    /** A valid {@code POST /api/v1/sales} body: {@code quantity} × 1 000 F, cash. */
    private Map<String, Object> saleBody(UUID id, UUID productId, UUID clientId, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("productName", "Savon 14.6");
        item.put("catalogueUnitPrice", 1000);
        item.put("appliedUnitPrice", 1000);
        item.put("quantity", quantity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleId", id);
        body.put("paymentMode", "CASH");
        body.put("clientId", clientId);
        body.put("storeId", storeId);
        body.put("discountAmount", 0);
        body.put("items", List.of(item));
        return body;
    }

    /** {@code GET /api/v1/invoices?saleId=} — the invoice(s) of a sale, as the app reads them. */
    private List<JsonNode> invoicesOfSale(UUID sale) throws Exception {
        JsonNode d = data("GET /invoices?saleId=", call(HttpMethod.GET, "/api/v1/invoices?saleId=" + sale, null),
                200);
        return d.isArray() ? iterate(d) : fail("liste attendue : " + d);
    }

    private static List<JsonNode> iterate(JsonNode array) {
        List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    /** Every invoice as listed by {@code GET /api/v1/invoices}: id → number / status / TTC / client / sale. */
    private Map<String, String> snapshot() throws Exception {
        JsonNode d = data("GET /invoices", call(HttpMethod.GET, "/api/v1/invoices?size=100", null), 200);
        Map<String, String> byId = new TreeMap<>();
        for (JsonNode inv : d) {
            byId.put(inv.path("id").asText(), inv.path("number").asText() + "|" + inv.path("status").asText()
                    + "|" + inv.path("totalTtc").asLong() + "|" + inv.path("client").path("id").asText() + "|"
                    + inv.path("saleId").asText());
        }
        return byId;
    }

    /** Waits until {@code expected} publications of the bridge listener for the sale are completed. */
    private void awaitBridgeCompleted(UUID sale, int expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM public.event_publication WHERE listener_id LIKE ? AND serialized_event LIKE ? "
                        + "AND completion_date IS NOT NULL", Integer.class, "%SaleInvoicingListener%",
                "%" + sale + "%")).as("relivraison de sale.completed traitée").isEqualTo(expected));
    }
}
