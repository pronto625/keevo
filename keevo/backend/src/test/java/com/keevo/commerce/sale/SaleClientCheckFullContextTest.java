package com.keevo.commerce.sale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase;
import com.keevo.catalog.product.application.usecase.CreateProductUseCase.CreateProductDto;
import com.keevo.identity.auth.application.service.TenantFactory;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.3 — {@code POST /api/v1/sales} with a client id on the real HTTP chain and PostgreSQL: an unknown client
 * with crm on is a clean 404 {@code CLIENT_NOT_FOUND} with nothing written (it used to hit the
 * {@code sales_client_id_fkey} FK → 500); with crm DISABLED the sale is recorded without its client and the stock is
 * decremented.
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Sale client check through the optional crm capability — full context (Story 14.3)")
class SaleClientCheckFullContextTest extends FullContextIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreateProductUseCase createProduct;
    @Autowired ModuleRegistry registry;

    private final UUID ownerId = UUID.randomUUID();
    private String schema;
    private UUID tenantId;
    private UUID storeId;
    private UUID productId;
    private String token;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        TenantContext.setCurrentTenant(schema);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ownerId, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        try {
            productId = createProduct.execute(new CreateProductDto("Savon 14.3", null, null, null, 500, null,
                    null, 0, ownerId, 0, null, "OWNER", null, null, null)).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
        if (jdbc.update("UPDATE \"" + schema + "\".stock_levels SET quantity = 50 WHERE product_id = ? AND store_id = ?",
                productId, storeId) == 0) {
            jdbc.update("INSERT INTO \"" + schema + "\".stock_levels (product_id, store_id, quantity) VALUES (?, ?, 50)",
                    productId, storeId);
        }
        token = jwtTokenProvider.generateAccessToken(ownerId, schema, "OWNER");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        registry.evict(schema);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void crm(String status) {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) VALUES (?, 'crm', ?, "
                + "'FREE') ON CONFLICT (tenant_id, module_id) DO UPDATE SET status = EXCLUDED.status", tenantId, status);
        registry.evict(schema);
    }

    private MvcResult sell(UUID saleId, UUID clientId) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("productName", "Savon 14.3");
        item.put("catalogueUnitPrice", 500);
        item.put("appliedUnitPrice", 500);
        item.put("quantity", 2);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleId", saleId);
        body.put("paymentMode", "CASH");
        body.put("clientId", clientId);
        body.put("storeId", storeId);
        body.put("discountAmount", 0);
        body.put("items", List.of(item));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/sales")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT quantity FROM \"" + schema + "\".stock_levels WHERE product_id = ? "
                + "AND store_id = ? AND variant_id IS NULL", Integer.class, productId, storeId);
    }

    private int sales(UUID saleId) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".sales WHERE id = ?", Integer.class, saleId);
    }

    @Test
    @DisplayName("crm on, unknown clientId → 404 CLIENT_NOT_FOUND « Client introuvable » (was 500), nothing written")
    void unknownClient_crmOn_404() throws Exception {
        UUID saleId = UUID.randomUUID();
        MvcResult r = sell(saleId, UUID.randomUUID());
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(404);
        assertThat(body(r)).contains("\"domainCode\":\"CLIENT_NOT_FOUND\"").contains("Client introuvable");
        assertThat(sales(saleId)).isZero();
        assertThat(stock()).isEqualTo(50);
    }

    @Test
    @DisplayName("crm on, known client → 201, sale linked to its client")
    void knownClient_crmOn_linked() throws Exception {
        UUID clientId = jdbc.queryForObject("INSERT INTO \"" + schema + "\".clients (name, phone) VALUES "
                + "('Awa', '+237690000021') RETURNING id", UUID.class);
        UUID saleId = UUID.randomUUID();
        MvcResult r = sell(saleId, clientId);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT client_id FROM \"" + schema + "\".sales WHERE id = ?", UUID.class,
                saleId)).isEqualTo(clientId);
        assertThat(stock()).isEqualTo(48);
    }

    @Test
    @DisplayName("crm DISABLED, clientId set → sale stored with client_id null, stock decremented")
    void crmOff_saleWithoutClient() throws Exception {
        crm("DISABLED");
        UUID saleId = UUID.randomUUID();
        MvcResult r = sell(saleId, UUID.randomUUID());
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT client_id FROM \"" + schema + "\".sales WHERE id = ?", UUID.class,
                saleId)).isNull();
        assertThat(stock()).isEqualTo(48);
    }
}
