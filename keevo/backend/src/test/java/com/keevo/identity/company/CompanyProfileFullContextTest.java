package com.keevo.identity.company;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportRequest;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.2 — company identity on the real HTTP chain: designation + logo (owner only), display rule (designation ›
 * shop name), audit, and the export PDF header following the rule.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Company identity — full context (Story 14.2)")
class CompanyProfileFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestExportUseCase requestExport;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private String schema;
    private String ownerToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        jdbc.update("UPDATE public.tenants SET name = 'Boutique Awa' WHERE schema_name = ?", schema);
        UUID storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", employeeId, storeId);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
        employeeToken = jwtTokenProvider.generateAccessToken(employeeId, schema, "EMPLOYEE", "ACTIVE", storeId,
                false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private MvcResult call(String token, HttpMethod method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult upload(String token, byte[] bytes, String contentType) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", contentType, bytes);
        return mockMvc.perform(MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/api/v1/account/company/logo")
                .file(file).header("Authorization", "Bearer " + token)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        return objectMapper.readTree(body(r)).path("data");
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    /** « Liste des ventes » PDF of the tenant (no sale: header only) — its text. */
    private String exportPdfText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantContext.setCurrentTenant(schema);
        try {
            requestExport.request(schema, ownerId, new ExportRequest(List.of("sales"), ExportFormat.PDF,
                    ExportFilter.none()), (ct, name) -> out);
        } finally {
            TenantContext.clear();
        }
        try (PDDocument doc = Loader.loadPDF(out.toByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("designation: owner sets it (export PDF header shows it), blank → shop name again; employee reads, "
            + "cannot write")
    void designation() throws Exception {
        JsonNode initial = data(call(ownerToken, HttpMethod.GET, "/api/v1/account/company", null), 200);
        assertThat(initial.path("designation").isNull()).isTrue();
        assertThat(initial.path("displayName").asText()).isEqualTo("Boutique Awa");
        assertThat(initial.path("hasLogo").asBoolean()).isFalse();
        assertThat(exportPdfText()).contains("Boutique Awa");

        JsonNode set = data(call(ownerToken, HttpMethod.PUT, "/api/v1/account/company",
                Map.of("designation", "Tech Centre SARL")), 200);
        assertThat(set.path("displayName").asText()).isEqualTo("Tech Centre SARL");
        assertThat(set.path("shopName").asText()).isEqualTo("Boutique Awa");
        assertThat(jdbc.queryForObject("SELECT designation FROM public.tenants WHERE schema_name = ?", String.class,
                schema)).isEqualTo("Tech Centre SARL");
        assertThat(exportPdfText()).contains("Tech Centre SARL").doesNotContain("Boutique Awa");

        assertThat(data(call(employeeToken, HttpMethod.GET, "/api/v1/account/company", null), 200)
                .path("displayName").asText()).isEqualTo("Tech Centre SARL");
        assertThat(call(employeeToken, HttpMethod.PUT, "/api/v1/account/company",
                Map.of("designation", "Pirate")).getResponse().getStatus()).isEqualTo(403);

        JsonNode cleared = data(call(ownerToken, HttpMethod.PUT, "/api/v1/account/company",
                Map.of("designation", "   ")), 200);
        assertThat(cleared.path("designation").isNull()).isTrue();
        assertThat(cleared.path("displayName").asText()).isEqualTo("Boutique Awa");
        assertThat(jdbc.queryForObject("SELECT designation FROM public.tenants WHERE schema_name = ?", String.class,
                schema)).isNull();
        assertThat(exportPdfText()).contains("Boutique Awa");

        MvcResult tooLong = call(ownerToken, HttpMethod.PUT, "/api/v1/account/company",
                Map.of("designation", "x".repeat(151)));
        assertThat(tooLong.getResponse().getStatus()).isBetween(400, 422);

        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, entity_label FROM \"" + schema
                + "\".audit_log WHERE action = 'COMPANY_PROFILE_UPDATED' ORDER BY occurred_at");
        assertThat(audit).hasSize(2);
        assertThat(audit).extracting(r -> String.valueOf(r.get("entity_label")))
                .containsExactly("Tech Centre SARL", "Boutique Awa")
                .noneMatch(l -> l.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*"));
    }

    @Test
    @DisplayName("logo: owner uploads a PNG (hasLogo, GET bytes), wrong type / too large → 422, employee → 403, delete")
    void logo() throws Exception {
        JsonNode uploaded = data(upload(ownerToken, png(800, 200), "image/png"), 200);
        assertThat(uploaded.path("hasLogo").asBoolean()).isTrue();
        MvcResult image = call(employeeToken, HttpMethod.GET, "/api/v1/account/company/logo", null);
        assertThat(image.getResponse().getStatus()).isEqualTo(200);
        assertThat(image.getResponse().getContentType()).isEqualTo("image/png");
        BufferedImage stored = ImageIO.read(new java.io.ByteArrayInputStream(image.getResponse().getContentAsByteArray()));
        assertThat(stored.getWidth()).isEqualTo(600);

        MvcResult gif = upload(ownerToken, "GIF89a-not-an-image-at-all".getBytes(), "image/gif");
        assertThat(gif.getResponse().getStatus()).as(body(gif)).isEqualTo(422);
        assertThat(body(gif)).contains("COMPANY_LOGO_INVALID").contains("PNG ou JPEG");
        MvcResult big = upload(ownerToken, new byte[250 * 1024], "image/png");
        assertThat(big.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(big)).contains("COMPANY_LOGO_TOO_LARGE");
        assertThat(upload(employeeToken, png(10, 10), "image/png").getResponse().getStatus()).isEqualTo(403);

        assertThat(data(call(ownerToken, HttpMethod.DELETE, "/api/v1/account/company/logo", null), 200)
                .path("hasLogo").asBoolean()).isFalse();
        MvcResult none = call(ownerToken, HttpMethod.GET, "/api/v1/account/company/logo", null);
        assertThat(none.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(none)).contains("Aucun logo");
    }
}
