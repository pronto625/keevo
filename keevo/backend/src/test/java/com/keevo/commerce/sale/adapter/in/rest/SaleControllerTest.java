package com.keevo.commerce.sale.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SaleControllerTest {

    @Mock private RecordSaleUseCase recordSaleUseCase;
    @Mock private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private UUID actorId;
    private UUID storeId;
    private UUID productId;
    private UUID saleId;

    @BeforeEach
    void setUp() {
        var controller = new SaleController(recordSaleUseCase, jwtTokenProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        productId = UUID.randomUUID();
        saleId = UUID.randomUUID();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private Claims claimsWithStore(String role) {
        return Jwts.claims()
                .subject(actorId.toString())
                .add("role", role)
                .add("storeId", storeId.toString())
                .add("tenantId", "kv_abc123")
                .build();
    }

    private void mockJwt(String role) {
        authenticateAs(role);
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(claimsWithStore(role));
        when(jwtTokenProvider.extractStoreId(any(Claims.class))).thenReturn(storeId);
        when(jwtTokenProvider.extractRole(any(Claims.class))).thenReturn(role);
    }

    private String validPayload() throws Exception {
        return mapper.writeValueAsString(Map.of(
                "saleId", saleId,
                "paymentMode", "CASH",
                "items", List.of(Map.of(
                        "productId", productId,
                        "productName", "Produit Test",
                        "appliedUnitPrice", 1500,
                        "quantity", 2
                ))
        ));
    }

    @Test
    void POST_sales_returns201_withValidPayload() throws Exception {
        mockJwt("EMPLOYEE");

        mockMvc.perform(post("/api/v1/sales")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(saleId.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.totalAmount").value(3000));

        verify(recordSaleUseCase).recordSale(any());
    }

    @Test
    void POST_sales_returns422_withInsufficientStock() throws Exception {
        mockJwt("EMPLOYEE");
        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK, "not enough"))
                .when(recordSaleUseCase).recordSale(any());

        mockMvc.perform(post("/api/v1/sales")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void POST_sales_returns400_withEmptyItems() throws Exception {
        authenticateAs("EMPLOYEE");

        String payload = mapper.writeValueAsString(Map.of(
                "saleId", saleId,
                "paymentMode", "CASH",
                "items", List.of()
        ));

        mockMvc.perform(post("/api/v1/sales")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void POST_sales_returns403_storeIdMismatch() throws Exception {
        authenticateAs("EMPLOYEE");
        // Employee with no storeId in JWT
        var claims = Jwts.claims()
                .subject(actorId.toString())
                .add("role", "EMPLOYEE")
                .add("tenantId", "kv_abc123")
                .build();
        when(jwtTokenProvider.parseToken(anyString())).thenReturn(claims);
        when(jwtTokenProvider.extractStoreId(any(Claims.class))).thenReturn(null);
        when(jwtTokenProvider.extractRole(any(Claims.class))).thenReturn("EMPLOYEE");

        mockMvc.perform(post("/api/v1/sales")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload()))
                .andExpect(status().isForbidden());
    }
}
