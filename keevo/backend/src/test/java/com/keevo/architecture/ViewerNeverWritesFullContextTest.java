package com.keevo.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import com.keevo.shared.infrastructure.security.TestAccess;
import com.keevo.shared.infrastructure.security.ViewerWriteGuardFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.AntPathMatcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.5 — a VIEWER never writes, checked on the real context over every non-GET REST handler of the application
 * ({@link EndpointEntitlementCoverageTest#scan}), minus the public paths, the super-admin API and the self-service
 * allow-list: each answers 403 {@code FORBIDDEN} with the shared French message before reaching the handler. The
 * same VIEWER reads like an EMPLOYEE (200) and keeps its self-service writes.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("VIEWER never writes — every non-GET endpoint, full context (Story 19.5)")
class ViewerNeverWritesFullContextTest extends FullContextIntegrationTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private String viewerToken;

    @BeforeEach
    void setUp() {
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        UUID store = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        UUID viewer = UUID.randomUUID();
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, role, username) "
                + "VALUES (?, now(), now(), true, 0, 'VIEWER', ?)", viewer, "lecteur-" + viewer.toString().substring(0, 8));
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Léa', 'Lecteur', false)", UUID.randomUUID(), viewer, store);
        viewerToken = TestAccess.token(jdbc, jwtTokenProvider, viewer, schema, "VIEWER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Every non-GET handler a VIEWER could call: not public, not super-admin, not self-service. */
    static Stream<String[]> writeEndpoints() throws Exception {
        List<String> problems = new ArrayList<>();
        List<String[]> out = new ArrayList<>();
        for (EndpointEntitlementCoverageTest.Endpoint e : EndpointEntitlementCoverageTest.scan(problems)) {
            String path = e.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            if (isPublic(path) || MATCHER.match("/api/v1/admin/**", path)) {
                continue;
            }
            for (String verb : ViewerAllowListPinTest.writeVerbs(e.verb())) { // every write verb, all four for ANY
                if (!ViewerWriteGuardFilter.isAllowed(verb, path)) {
                    out.add(new String[]{verb, path, e.handler()});
                }
            }
        }
        assertThat(out).as("non-GET endpoints under test").hasSizeGreaterThan(50);
        return out.stream();
    }

    private static boolean isPublic(String path) {
        return Arrays.stream(SecurityConfig.PUBLIC_PATHS).anyMatch(p -> MATCHER.match(p, path));
    }

    private MvcResult call(String verb, String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(verb), path)
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
    }

    @ParameterizedTest(name = "{0} {1} ({2})")
    @MethodSource("writeEndpoints")
    @DisplayName("each write → 403 FORBIDDEN, shared French message")
    void everyWriteIsForbidden(String verb, String path, String handler) throws Exception {
        MvcResult r = call(verb, path);
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as("%s %s (%s): %s", verb, path, handler, body).isEqualTo(403);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.path("domainCode").asText()).isEqualTo("FORBIDDEN");
        assertThat(json.path("error").asText()).isEqualTo("Vous n'avez pas les droits nécessaires");
    }

    @Test
    @DisplayName("a VIEWER reads like an EMPLOYEE: GET products, categories, profile → 200")
    void readsAreAllowed() throws Exception {
        for (String path : List.of("/api/v1/products", "/api/v1/categories", "/api/v1/auth/profile")) {
            MvcResult r = mockMvc.perform(MockMvcRequestBuilders.get(path)
                    .header("Authorization", "Bearer " + viewerToken)).andReturn();
            assertThat(r.getResponse().getStatus()).as("%s: %s", path,
                    r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        }
        JsonNode profile = objectMapper.readTree(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/profile")
                        .header("Authorization", "Bearer " + viewerToken)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(profile.path("role").asText()).isEqualTo("EMPLOYEE"); // V1 vocabulary
        assertThat(profile.path("memberRole").asText()).isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("self-service writes are not blocked by the VIEWER rule (notification read, push token)")
    void selfServiceWritesPass() throws Exception {
        MvcResult read = call("PATCH", "/api/v1/notifications/" + UUID.randomUUID() + "/read");
        assertThat(read.getResponse().getStatus()).as(read.getResponse().getContentAsString()).isNotEqualTo(403);
        MvcResult readAll = call("PATCH", "/api/v1/notifications/read-all");
        assertThat(readAll.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("Vous n'avez pas les droits nécessaires");
    }
}
