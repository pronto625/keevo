package com.keevo.inventory.counting.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.inventory.counting.domain.model.InventoryCount;
import com.keevo.inventory.counting.domain.model.InventoryProductRow;
import com.keevo.inventory.counting.domain.model.InventoryScope;
import com.keevo.inventory.counting.domain.model.InventorySession;
import com.keevo.inventory.counting.domain.model.InventorySessionStatus;
import com.keevo.inventory.counting.domain.port.in.*;
import com.keevo.inventory.counting.domain.port.out.InventorySessionRepository;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryCountController")
class InventoryCountControllerTest {

    @Mock private GetCountingProductsUseCase getCountingProductsUseCase;
    @Mock private SaveInventoryCountUseCase saveInventoryCountUseCase;
    @Mock private GetSessionCountsUseCase getSessionCountsUseCase;
    @Mock private QuickAddProductUseCase quickAddProductUseCase;
    @Mock private InventorySessionRepository sessionRepository;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectMapper objectMapper = mapper;

    private UUID actorId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        var controller = new InventoryCountController(
                getCountingProductsUseCase, saveInventoryCountUseCase, getSessionCountsUseCase,
                quickAddProductUseCase, sessionRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        mapper.findAndRegisterModules();
    }

    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_C = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** EMPLOYEE tokens carry their assigned store (STORE_A), as JwtAuthFilter sets it. */
    private void authenticateAs(String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                actorId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        if ("EMPLOYEE".equals(role)) {
            auth.setDetails(new AuthDetails("Loïc", STORE_A));
        }
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private InventorySession sessionOf(UUID storeId) {
        return new InventorySession(sessionId, storeId, InventoryScope.FULL, null,
                InventorySessionStatus.IN_PROGRESS, actorId, Instant.now(),
                null, null, null, Instant.now());
    }

    private String singleCountBody() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", UUID.randomUUID());
        body.put("productName", "Produit A");
        body.put("theoretical", 50);
        body.put("physical", 47);
        return objectMapper.writeValueAsString(body);
    }

    private String bulkCountBody() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", UUID.randomUUID());
        item.put("productName", "P");
        item.put("theoretical", 10);
        item.put("physical", 8);
        return objectMapper.writeValueAsString(Map.of("counts", List.of(item)));
    }

    // ── v1s-12-10: counts scoped to the employee's store ─────

    @Test
    void POST_counts_employeeForeignStoreSession_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(sessionOf(STORE_C)));

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleCountBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(saveInventoryCountUseCase);
    }

    @Test
    void PUT_counts_employeeForeignStoreSession_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(sessionOf(STORE_C)));

        mockMvc.perform(put("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkCountBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(saveInventoryCountUseCase);
    }

    @Test
    void POST_counts_employeeOwnStoreSession_shouldReturn201() throws Exception {
        authenticateAs("EMPLOYEE");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(sessionOf(STORE_A)));
        when(saveInventoryCountUseCase.execute(any())).thenReturn(InventoryCount.create(
                sessionId, UUID.randomUUID(), null, "Produit A", null, 50, 47, actorId));

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleCountBody()))
                .andExpect(status().isCreated());
    }

    /** EMPLOYEE token without an assigned store (no AuthDetails storeId) — fails closed. */
    private void authenticateAsEmployeeWithoutStore() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }

    @Test
    void POST_counts_employeeWithoutAssignedStore_shouldReturn403() throws Exception {
        authenticateAsEmployeeWithoutStore();

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleCountBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(saveInventoryCountUseCase);
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void PUT_counts_employeeWithoutAssignedStore_shouldReturn403() throws Exception {
        authenticateAsEmployeeWithoutStore();

        mockMvc.perform(put("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkCountBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(saveInventoryCountUseCase);
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void PUT_counts_ownerAnyStore_isUnscoped() throws Exception {
        authenticateAs("OWNER");
        when(saveInventoryCountUseCase.execute(any())).thenReturn(InventoryCount.create(
                sessionId, UUID.randomUUID(), null, "P", null, 10, 8, actorId));

        mockMvc.perform(put("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkCountBody()))
                .andExpect(status().isOk());

        verifyNoInteractions(sessionRepository);
    }

    @Test
    void POST_counts_employeeUnknownSession_isDelegatedToUseCase() throws Exception {
        authenticateAs("EMPLOYEE");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());
        when(saveInventoryCountUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND, "Not found"));

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleCountBody()))
                .andExpect(status().isNotFound());
    }

    // ── GET /products ────────────────────────────────────────

    @Test
    void GET_products_shouldReturn200WithList() throws Exception {
        authenticateAs("OWNER");
        UUID productId = UUID.randomUUID();
        var row = InventoryProductRow.of(productId, "Savon bio", null, null, null, null, 100, null);
        when(getCountingProductsUseCase.execute(any())).thenReturn(List.of(row));

        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/products", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productName").value("Savon bio"))
                .andExpect(jsonPath("$.data[0].theoreticalQty").value(100))
                .andExpect(jsonPath("$.data[0].physicalQty").isEmpty());
    }

    @Test
    void GET_products_sessionNotFound_shouldReturn404() throws Exception {
        authenticateAs("OWNER");
        when(getCountingProductsUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND, "Not found"));

        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/products", sessionId))
                .andExpect(status().isNotFound());
    }

    // ── POST /counts ─────────────────────────────────────────

    @Test
    void POST_counts_shouldReturn201WithCount() throws Exception {
        authenticateAs("EMPLOYEE");
        UUID productId = UUID.randomUUID();
        var saved = InventoryCount.create(sessionId, productId, null,
                "Produit A", null, 50, 47, actorId);
        when(saveInventoryCountUseCase.execute(any())).thenReturn(saved);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("productName", "Produit A");
        body.put("theoretical", 50);
        body.put("physical", 47);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productName").value("Produit A"))
                .andExpect(jsonPath("$.data.physical").value(47))
                .andExpect(jsonPath("$.data.theoretical").value(50));
    }

    @Test
    void POST_counts_sessionNotInProgress_shouldReturn409() throws Exception {
        authenticateAs("OWNER");
        when(saveInventoryCountUseCase.execute(any())).thenThrow(
                new DomainException(ErrorCode.INVENTORY_SESSION_NOT_IN_PROGRESS,
                        "Session not in progress"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", UUID.randomUUID());
        body.put("productName", "P");
        body.put("theoretical", 10);
        body.put("physical", 8);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void POST_counts_missingProductName_shouldReturn400() throws Exception {
        authenticateAs("OWNER");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", UUID.randomUUID());
        body.put("productName", "");  // blank
        body.put("theoretical", 10);
        body.put("physical", 8);

        mockMvc.perform(post("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET /counts ──────────────────────────────────────────

    @Test
    void GET_counts_shouldReturnAllCountsForSession() throws Exception {
        authenticateAs("OWNER");
        var count = InventoryCount.create(sessionId, UUID.randomUUID(), null,
                "P", null, 10, 8, UUID.randomUUID());
        when(getSessionCountsUseCase.execute(any())).thenReturn(List.of(count));

        mockMvc.perform(get("/api/v1/inventory/sessions/{sessionId}/counts", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].theoretical").value(10))
                .andExpect(jsonPath("$.data[0].physical").value(8));
    }

    // ── PUT /counts (bulk) ───────────────────────────────────

    @Test
    void PUT_counts_shouldReturn200WithBulkResults() throws Exception {
        authenticateAs("OWNER");
        var saved = InventoryCount.create(sessionId, UUID.randomUUID(), null,
                "P", null, 10, 8, actorId);
        when(saveInventoryCountUseCase.execute(any())).thenReturn(saved);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", UUID.randomUUID());
        item.put("productName", "P");
        item.put("theoretical", 10);
        item.put("physical", 8);

        Map<String, Object> body = Map.of("counts", List.of(item));

        mockMvc.perform(put("/api/v1/inventory/sessions/{sessionId}/counts", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productName").value("P"));
    }
}
