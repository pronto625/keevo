package com.keevo.commerce.sale.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.commerce.sale.domain.model.*;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CorrectSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.GetPendingSalesUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PendingSaleControllerTest {

    @Mock private GetPendingSalesUseCase getPendingSalesUseCase;
    @Mock private ValidateSaleUseCase validateSaleUseCase;
    @Mock private CancelPendingSaleUseCase cancelPendingSaleUseCase;
    @Mock private CorrectSaleUseCase correctSaleUseCase;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private UUID storeId;
    private UUID saleId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        var controller = new PendingSaleController(
                getPendingSalesUseCase, validateSaleUseCase, cancelPendingSaleUseCase, correctSaleUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        saleId = UUID.randomUUID();
        productId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void authenticateAsEmployee(UUID storeId) {
        var auth = new UsernamePasswordAuthenticationToken(
                actorId, null,
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        auth.setDetails(storeId);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Sale pendingSale() {
        var item = new SaleItem(
                UUID.randomUUID(), saleId, productId, null,
                "Produit Draft", 1500, 1500, 2);
        return new Sale(
                saleId, storeId, actorId, null,
                PaymentMode.CASH, 3000, 0,
                SaleStatus.PENDING_VALIDATION,
                Instant.now(), Instant.now(),
                List.of(item));
    }

    // ── GET /api/v1/sales/pending ───────────────────────────────────────────

    @Test
    void GET_pending_returns200_withSalesList() throws Exception {
        authenticateAs("OWNER");
        when(getPendingSalesUseCase.getPendingSales()).thenReturn(List.of(pendingSale()));

        mockMvc.perform(get("/api/v1/sales/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(saleId.toString()))
                .andExpect(jsonPath("$.data[0].status").value("PENDING_VALIDATION"))
                .andExpect(jsonPath("$.data[0].totalAmount").value(3000))
                .andExpect(jsonPath("$.data[0].items", hasSize(1)));
    }

    @Test
    void GET_pending_returns200_emptyList() throws Exception {
        authenticateAs("OWNER");
        when(getPendingSalesUseCase.getPendingSales()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sales/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ── POST /api/v1/sales/{id}/validate ────────────────────────────────────

    @Test
    void POST_validate_returns200_withValidJustification() throws Exception {
        authenticateAs("OWNER");

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Produit vérifié et stock confirmé par le fournisseur"));

        mockMvc.perform(post("/api/v1/sales/{id}/validate", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").exists());

        verify(validateSaleUseCase).validateSale(any());
    }

    @Test
    void POST_validate_returns422_saleNotPending() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.SALE_NOT_PENDING, "not pending"))
                .when(validateSaleUseCase).validateSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Justification suffisamment longue"));

        mockMvc.perform(post("/api/v1/sales/{id}/validate", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("SALE_NOT_PENDING"));
    }

    @Test
    void POST_validate_returns400_justificationTooShort() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.JUSTIFICATION_TOO_SHORT, "too short"))
                .when(validateSaleUseCase).validateSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "court"));

        mockMvc.perform(post("/api/v1/sales/{id}/validate", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domainCode").value("JUSTIFICATION_TOO_SHORT"));
    }

    @Test
    void POST_validate_returns400_justificationBlank() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.JUSTIFICATION_REQUIRED, "required"))
                .when(validateSaleUseCase).validateSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", ""));

        mockMvc.perform(post("/api/v1/sales/{id}/validate", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domainCode").value("JUSTIFICATION_REQUIRED"));
    }

    // ── POST /api/v1/sales/{id}/cancel ──────────────────────────────────────

    @Test
    void POST_cancel_returns200_withValidJustification() throws Exception {
        authenticateAs("OWNER");

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Client a changé d'avis, annulation demandée"));

        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").exists());

        verify(cancelPendingSaleUseCase).cancelPendingSale(any());
    }

    @Test
    void POST_cancel_returns422_saleNotPending() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.SALE_NOT_PENDING, "not pending"))
                .when(cancelPendingSaleUseCase).cancelPendingSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Annulation pour raison valable ici"));

        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("SALE_NOT_PENDING"));
    }

    @Test
    void POST_cancel_returns400_justificationBlank() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.JUSTIFICATION_REQUIRED, "required"))
                .when(cancelPendingSaleUseCase).cancelPendingSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", ""));

        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domainCode").value("JUSTIFICATION_REQUIRED"));
    }

    // ── Story v1s-13-5 — cancel on COMPLETED sale + correct endpoint ─────────

    @Test
    void POST_cancel_completedSale_returns200() throws Exception {
        authenticateAs("OWNER");
        // Controller doesn't know the sale status — mock simply succeeds, proving
        // no controller change was required for the COMPLETED branch to work.
        doNothing().when(cancelPendingSaleUseCase).cancelPendingSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Erreur de scan, article rendu au client"));

        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").exists());

        verify(cancelPendingSaleUseCase).cancelPendingSale(any());
    }

    @Test
    void POST_cancel_alreadyCancelled_returns409() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.SALE_ALREADY_CANCELLED, "already cancelled"))
                .when(cancelPendingSaleUseCase).cancelPendingSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Nouvelle tentative d'annulation ici"));

        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.domainCode").value("SALE_ALREADY_CANCELLED"));
    }

    @Test
    void POST_correct_returns200() throws Exception {
        authenticateAs("OWNER");

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Erreur de quantité scannée au comptoir",
                "itemQuantities", Map.of(productId.toString(), 5)));

        mockMvc.perform(post("/api/v1/sales/{id}/correct", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").exists());

        verify(correctSaleUseCase).correctSale(any());
    }

    @Test
    void POST_correct_employeeForbidden_returns403() throws Exception {
        authenticateAs("EMPLOYEE");

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Erreur de quantité scannée au comptoir",
                "itemQuantities", Map.of(productId.toString(), 5)));

        mockMvc.perform(post("/api/v1/sales/{id}/correct", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verify(correctSaleUseCase, never()).correctSale(any());
    }

    @Test
    void POST_correct_notCompleted_returns422() throws Exception {
        authenticateAs("OWNER");
        doThrow(new DomainException(ErrorCode.SALE_NOT_COMPLETED, "not completed"))
                .when(correctSaleUseCase).correctSale(any());

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Erreur de quantité scannée au comptoir",
                "itemQuantities", Map.of(productId.toString(), 5)));

        mockMvc.perform(post("/api/v1/sales/{id}/correct", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("SALE_NOT_COMPLETED"));
    }

    // ── Story 12.6 — RBAC tests ─────────────────────────────────────────

    @Test
    void GET_pending_employeeOwnStore_returns200() throws Exception {
        authenticateAsEmployee(storeId);
        when(getPendingSalesUseCase.getPendingSalesByStore(storeId))
                .thenReturn(List.of(pendingSale()));

        mockMvc.perform(get("/api/v1/sales/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));

        verify(getPendingSalesUseCase).getPendingSalesByStore(storeId);
        verify(getPendingSalesUseCase, never()).getPendingSales();
    }

    @Test
    void POST_cancel_employeeForbidden_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");

        String payload = mapper.writeValueAsString(Map.of(
                "justification", "Annulation demandée par le client"));

        mockMvc.perform(post("/api/v1/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        verify(cancelPendingSaleUseCase, never()).cancelPendingSale(any());
    }
}
