package com.keevo.inventory.counting.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.product.domain.entity.ProductStatus;
import com.keevo.catalog.stock.domain.entity.StockLevel;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.QuickAddProductResult;
import com.keevo.inventory.counting.domain.port.in.*;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuickAddProductController — POST /quick-add endpoint")
class QuickAddProductControllerTest {

    @Mock private GetCountingProductsUseCase getCountingProductsUseCase;
    @Mock private SaveInventoryCountUseCase saveInventoryCountUseCase;
    @Mock private GetSessionCountsUseCase getSessionCountsUseCase;
    @Mock private QuickAddProductUseCase quickAddProductUseCase;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        var controller = new InventoryCountController(
                getCountingProductsUseCase, saveInventoryCountUseCase,
                getSessionCountsUseCase, quickAddProductUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        mapper.findAndRegisterModules();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("POST /quick-add → 201 with product + count created")
    void quickAdd_happyPath_returns201() throws Exception {
        authenticateAs("OWNER");
        UUID categoryId = UUID.randomUUID();
        Instant now = Instant.now();

        Product product = new Product(UUID.randomUUID(), "Robe Wax L", null, "KEV-ABC123",
                categoryId, 0, 0, 0, 5, false, ProductStatus.ACTIVE, 0, now, now);
        StockLevel stockLevel = new StockLevel(UUID.randomUUID(), product.getId(), null,
                UUID.randomUUID(), 5, now);
        InventoryCount count = InventoryCount.create(sessionId, product.getId(), null,
                "Robe Wax L", null, 0, 5, actorId);

        when(quickAddProductUseCase.execute(any())).thenReturn(
                new QuickAddProductResult(product, stockLevel, count));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Robe Wax L");
        body.put("categoryId", categoryId);
        body.put("physicalQty", 5);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productName").value("Robe Wax L"))
                .andExpect(jsonPath("$.data.sku").value("KEV-ABC123"))
                .andExpect(jsonPath("$.data.physicalQty").value(5));
    }

    @Test
    @DisplayName("POST /quick-add → 409 when product name already exists (dedup)")
    void quickAdd_duplicateName_returns409() throws Exception {
        authenticateAs("OWNER");
        when(quickAddProductUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.PRODUCT_NAME_ALREADY_EXISTS,
                        "Un produit avec le nom 'Robe' existe déjà"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Robe");
        body.put("categoryId", UUID.randomUUID());
        body.put("physicalQty", 3);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /quick-add → 404 when session not found")
    void quickAdd_sessionNotFound_returns404() throws Exception {
        authenticateAs("EMPLOYEE");
        when(quickAddProductUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND, "Not found"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Produit test");
        body.put("categoryId", UUID.randomUUID());
        body.put("physicalQty", 1);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /quick-add → 422 when validation fails (blank name, null category, negative qty)")
    void quickAdd_validationErrors_returns422() throws Exception {
        authenticateAs("OWNER");

        // Blank name
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "");
        body.put("categoryId", UUID.randomUUID());
        body.put("physicalQty", 1);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());

        // Null category
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("name", "Valid name");
        body2.put("categoryId", null);
        body2.put("physicalQty", 1);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body2)))
                .andExpect(status().isUnprocessableEntity());

        // Negative qty
        Map<String, Object> body3 = new LinkedHashMap<>();
        body3.put("name", "Valid name");
        body3.put("categoryId", UUID.randomUUID());
        body3.put("physicalQty", -1);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body3)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /quick-add → RBAC: both OWNER and EMPLOYEE can access")
    void quickAdd_bothRolesAllowed() throws Exception {
        UUID categoryId = UUID.randomUUID();
        Instant now = Instant.now();
        Product product = new Product(UUID.randomUUID(), "P", null, "KEV-XYZ789",
                categoryId, 0, 0, 0, 1, false, ProductStatus.ACTIVE, 0, now, now);
        StockLevel sl = new StockLevel(UUID.randomUUID(), product.getId(), null,
                UUID.randomUUID(), 1, now);
        InventoryCount count = InventoryCount.create(sessionId, product.getId(), null,
                "P", null, 0, 1, actorId);
        when(quickAddProductUseCase.execute(any())).thenReturn(
                new QuickAddProductResult(product, sl, count));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "P");
        body.put("categoryId", categoryId);
        body.put("physicalQty", 1);

        // OWNER
        authenticateAs("OWNER");
        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // EMPLOYEE
        authenticateAs("EMPLOYEE");
        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/quick-add", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
}
