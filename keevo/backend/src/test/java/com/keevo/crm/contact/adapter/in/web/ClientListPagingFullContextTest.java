package com.keevo.crm.contact.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.crm.api.DocumentClientInput;
import com.keevo.crm.contact.application.service.ClientAccess;
import com.keevo.crm.contact.application.usecase.DeriveClientFromDocumentUseCase;
import com.keevo.crm.contact.domain.port.out.ClientRepository;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.6 — the client list at scale on the real chain (PostgreSQL, crm V3 indexes): opt-in keyset pages of
 * {@code GET /api/v1/clients?limit=&cursor=}, the unchanged unbounded list without {@code limit}, the search on the
 * trigram-indexed shape, and the document-client derivation's SQL (store match, legacy fallback, COALESCE fill).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("20.6 — liste des clients paginée, recherche trigram, dérivation (full context)")
class ClientListPagingFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider jwt;
    @Autowired ClientRepository clientRepository;
    @Autowired DeriveClientFromDocumentUseCase derive;

    private String schema;
    private UUID storeA;
    private String owner;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        storeA = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores ORDER BY created_at LIMIT 1",
                UUID.class);
        owner = TestAccess.token(jdbc, jwt, UUID.randomUUID(), schema, "OWNER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID client(String name, String phone, UUID storeId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".clients (id, name, phone, phone_normalized, store_id, archived, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, false, ?, ?)", id, name, phone, phone, storeId,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private MvcResult get(String url) throws Exception {
        return get(owner, url);
    }

    private MvcResult get(String token, String url) throws Exception {
        // a URI, not a template: the test URL is already encoded (%25 stays one « % »)
        return mockMvc.perform(MockMvcRequestBuilders.get(java.net.URI.create(url)).header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private UUID store(String name, int secondsAfter) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.now().plusSeconds(secondsAfter));
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'STORE', true, ?, ?)", id, name, at, at);
        return id;
    }

    private String memberToken(UUID storeId, String role) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Awa', 'Ndiaye', false)",
                UUID.randomUUID(), userId, storeId);
        return TestAccess.token(jdbc, jwt, userId, schema, role);
    }

    /** Every name of the paged list (following {@code nextCursor}), page size {@code limit}. */
    private List<String> pagedNames(String token, String query, int limit) throws Exception {
        List<String> names = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode response = json(get(token, "/api/v1/clients?limit=" + limit + query
                    + (cursor == null ? "" : "&cursor=" + cursor)), 200);
            pages++;
            assertThat(response.path("data").size()).isLessThanOrEqualTo(limit);
            response.path("data").forEach(c -> names.add(c.path("name").asText()));
            JsonNode next = response.path("meta").path("nextCursor");
            cursor = next.isMissingNode() || next.isNull() ? null : next.asText();
        } while (cursor != null && pages < 20);
        assertThat(cursor).as("last page reached").isNull();
        return names;
    }

    private JsonNode json(MvcResult r, int status) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("120 clients, limit=50: 3 pages in (lower(name), id) order through nextCursor; no limit → the same list as before")
    void keysetPages() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            // mixed case and duplicated names: the order is lower(name) then id
            String name = (i % 3 == 0 ? "client " : "Client ") + String.format("%03d", i % 100);
            client(name, String.format("+2376900%05d", i), storeA, Instant.now());
            expected.add(name);
        }
        List<String> names = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode response = json(get("/api/v1/clients?limit=50" + (cursor == null ? "" : "&cursor=" + cursor)),
                    200);
            pages++;
            response.path("data").forEach(c -> {
                names.add(c.path("name").asText());
                ids.add(c.path("id").asText());
            });
            JsonNode next = response.path("meta").path("nextCursor");
            cursor = next.isMissingNode() || next.isNull() ? null : next.asText();
            if (pages < 3) {
                assertThat(response.path("data")).hasSize(50);
                assertThat(cursor).isNotNull();
            }
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(3);
        assertThat(ids).hasSize(120);
        assertThat(names).isSortedAccordingTo(Comparator.comparing(n -> n.toLowerCase(Locale.ROOT)));
        assertThat(names).containsExactlyInAnyOrderElementsOf(expected);

        JsonNode all = json(get("/api/v1/clients"), 200);
        assertThat(all.path("data")).hasSize(120);
        assertThat(all.has("meta")).isFalse();
    }

    @Test
    @DisplayName("search with and without limit; bad cursor and limit → 400 VALIDATION_ERROR")
    void searchAndErrors() throws Exception {
        client("Mireille Ngo", "+237690000011", storeA, Instant.now());
        client("Amir Bello", "+237690000012", storeA, Instant.now());
        client("Paul Etoa", "+237690000014", storeA, Instant.now());

        JsonNode page = json(get("/api/v1/clients?search=MIR&limit=1"), 200);
        assertThat(page.path("data").get(0).path("name").asText()).isEqualTo("Amir Bello");
        String next = page.path("meta").path("nextCursor").asText();
        JsonNode second = json(get("/api/v1/clients?search=MIR&limit=1&cursor=" + next), 200);
        assertThat(second.path("data").get(0).path("name").asText()).isEqualTo("Mireille Ngo");
        assertThat(second.path("meta").path("nextCursor").isMissingNode()).isTrue();
        assertThat(json(get("/api/v1/clients?search=0000014"), 200).path("data").get(0).path("name").asText())
                .isEqualTo("Paul Etoa");

        JsonNode bad = json(get("/api/v1/clients?limit=10&cursor=nimportequoi"), 400);
        assertThat(bad.path("domainCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(bad.path("error").asText()).isEqualTo("Curseur de pagination invalide");
        assertThat(json(get("/api/v1/clients?limit=0"), 400).path("domainCode").asText())
                .isEqualTo("VALIDATION_ERROR");
        assertThat(json(get("/api/v1/clients?limit=101"), 400).path("domainCode").asText())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("crm V3: the trigram and keyset indexes exist on the tenant clients table")
    void indexes() {
        List<String> indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? "
                + "AND tablename = 'clients'", String.class, schema);
        assertThat(indexes).contains("idx_clients_name_lower_id", "idx_clients_name_trgm", "idx_clients_phone_trgm");
    }

    @Test
    @DisplayName("derivation SQL: store match before legacy, oldest first, COALESCE fill only of empty fields")
    void derivationMatchAndFill() {
        Instant old = Instant.now().minusSeconds(3600);
        UUID legacy = client("Ancien", "+237699000001", null, old.minusSeconds(60));
        UUID inStoreNew = client("Awa récente", "+237699000001", storeA, Instant.now());
        UUID inStoreOld = client("Awa", "+237699000001", storeA, old);
        jdbc.update("UPDATE \"" + schema + "\".clients SET rccm = 'RC-OLD' WHERE id = ?", inStoreOld);
        UUID onlyLegacy = client("Legacy seul", "+237699000002", null, old);

        TenantContext.setCurrentTenant(schema);
        assertThat(clientRepository.findDocumentMatch(storeA, "+237699000001")).contains(inStoreOld);
        assertThat(clientRepository.findDocumentMatch(storeA, "+237699000002")).contains(onlyLegacy);
        assertThat(clientRepository.findDocumentMatch(null, "+237699000001")).contains(legacy);
        assertThat(clientRepository.findDocumentMatch(storeA, "+237699000009")).isEmpty();

        Optional<UUID> linked = tx.execute(s -> derive.execute(new DocumentClientInput("Autre nom", "699 00 00 01",
                "awa@example.com", "RC-NEW", "NIU-1", null), ClientAccess.SYSTEM, UUID.randomUUID(), schema));
        assertThat(linked).contains(inStoreOld);
        Map<String, Object> row = jdbc.queryForMap("SELECT name, email, rccm, niu FROM \"" + schema
                + "\".clients WHERE id = ?", inStoreOld);
        assertThat(row).containsEntry("name", "Awa").containsEntry("email", "awa@example.com")
                .containsEntry("rccm", "RC-OLD").containsEntry("niu", "NIU-1");
        assertThat(inStoreNew).isNotNull();

        Optional<UUID> created = tx.execute(s -> derive.execute(new DocumentClientInput("Nouveau", "+237699000003",
                null, null, null, "Akwa"), ClientAccess.SYSTEM, UUID.randomUUID(), schema));
        assertThat(created).isPresent();
        Map<String, Object> made = jdbc.queryForMap("SELECT name, phone, phone_normalized, store_id, address FROM \""
                + schema + "\".clients WHERE id = ?", created.get());
        assertThat(made).containsEntry("name", "Nouveau").containsEntry("phone_normalized", "+237699000003")
                .containsEntry("store_id", storeA).containsEntry("address", "Akwa");

        Optional<UUID> badPhone = tx.execute(s -> derive.execute(new DocumentClientInput("X", "12", null, null, null,
                null), ClientAccess.SYSTEM, UUID.randomUUID(), schema));
        assertThat(badPhone).isEmpty();
        Optional<UUID> noStore = tx.execute(s -> derive.execute(new DocumentClientInput("X", "+237699000004", null,
                null, null, null), ClientAccess.member(null), UUID.randomUUID(), schema));
        assertThat(noStore).isEmpty();
    }

    @Test
    @DisplayName("store member, paged: only its store's clients and the legacy ones (no store), never another store's")
    void memberPagesAreStoreScoped() throws Exception {
        UUID storeB = store("Boutique B", 60);
        client("A1 Awa", "+237690000101", storeA, Instant.now());
        client("A2 Bello", "+237690000102", storeA, Instant.now());
        client("A3 Chantal", "+237690000103", storeA, Instant.now());
        client("B1 Daniel", "+237690000201", storeB, Instant.now());
        client("B2 Esther", "+237690000202", storeB, Instant.now());
        client("L1 Fabrice", "+237690000301", null, Instant.now());
        client("L2 Grace", "+237690000302", null, Instant.now());
        String memberA = memberToken(storeA, "EMPLOYEE");

        assertThat(pagedNames(memberA, "", 2)).containsExactly("A1 Awa", "A2 Bello", "A3 Chantal", "L1 Fabrice",
                "L2 Grace");
        assertThat(pagedNames(memberA, "&search=l", 1)).containsExactly("A2 Bello", "A3 Chantal", "L1 Fabrice",
                "L2 Grace").doesNotContain("B1 Daniel", "B2 Esther");
        // the owner sees every store
        assertThat(pagedNames(owner, "", 3)).hasSize(7);
    }

    @Test
    @DisplayName("archived client, paged: excluded by default, included with includeArchived=true, excluded when searching")
    void archivedInPages() throws Exception {
        client("Alice Active", "+237690000401", storeA, Instant.now());
        UUID archived = client("Alice Archivée", "+237690000402", storeA, Instant.now());
        jdbc.update("UPDATE \"" + schema + "\".clients SET archived = true WHERE id = ?", archived);

        assertThat(pagedNames(owner, "", 1)).containsExactly("Alice Active");
        assertThat(pagedNames(owner, "&includeArchived=true", 1)).containsExactly("Alice Active", "Alice Archivée");
        assertThat(pagedNames(owner, "&includeArchived=true&search=alice", 1)).containsExactly("Alice Active");
    }

    @Test
    @DisplayName("search « _ » or « % » matches the literal character only, never every client (paged and unpaged)")
    void wildcardSearchEscaped() throws Exception {
        client("Awa_Bis", "+237690000501", storeA, Instant.now());
        client("Promo 50% Paul", "+237690000502", storeA, Instant.now());
        client("Chantal", "+237690000503", storeA, Instant.now());

        assertThat(pagedNames(owner, "&search=_", 10)).containsExactly("Awa_Bis");
        assertThat(pagedNames(owner, "&search=%25", 10)).containsExactly("Promo 50% Paul");
        List<String> unpaged = new ArrayList<>();
        json(get("/api/v1/clients?search=_"), 200).path("data").forEach(c -> unpaged.add(c.path("name").asText()));
        assertThat(unpaged).containsExactly("Awa_Bis");
        List<String> memberUnpaged = new ArrayList<>(); // the store-scoped query (searchScoped) escapes the same way
        json(get(memberToken(storeA, "EMPLOYEE"), "/api/v1/clients?search=_"), 200).path("data")
                .forEach(c -> memberUnpaged.add(c.path("name").asText()));
        assertThat(memberUnpaged).containsExactly("Awa_Bis");
        assertThat(json(get("/api/v1/clients?search=%5C"), 200).path("data")).isEmpty();
    }
}
