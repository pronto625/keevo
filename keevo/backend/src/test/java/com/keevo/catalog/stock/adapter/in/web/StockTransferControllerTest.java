package com.keevo.catalog.stock.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.catalog.stock.application.usecase.ExecuteTransferService;
import com.keevo.catalog.stock.application.usecase.GetTransferHistoryService;
import com.keevo.catalog.stock.domain.model.StockTransfer;
import com.keevo.catalog.stock.domain.model.StockTransfer.TransferStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * StockTransferControllerTest — TDD RED phase first.
 * Task 9.3 — Story 3.3.
 */
@ExtendWith(MockitoExtension.class)
class StockTransferControllerTest {

    @Mock private ExecuteTransferService executeTransferService;
    @Mock private GetTransferHistoryService getTransferHistoryService;

    @InjectMocks private StockTransferController controller;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private UUID srcId;
    private UUID destId;
    private UUID prodId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        srcId   = UUID.randomUUID();
        destId  = UUID.randomUUID();
        prodId  = UUID.randomUUID();
        actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of()));
    }

    private StockTransfer completedTransfer() {
        return new StockTransfer(UUID.randomUUID(), srcId, destId, prodId, null, 5,
            actorId, Instant.now(), TransferStatus.COMPLETED, null);
    }

    @Test
    void POST_stock_transfers_shouldReturn201_withValidBody() throws Exception {
        when(executeTransferService.execute(any())).thenReturn(completedTransfer());

        mockMvc.perform(post("/api/v1/stock/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourceStoreId", srcId,
                    "destinationStoreId", destId,
                    "productId", prodId,
                    "quantity", 5
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void POST_stock_transfers_shouldReturn422_whenQuantityZero() throws Exception {
        mockMvc.perform(post("/api/v1/stock/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourceStoreId", srcId,
                    "destinationStoreId", destId,
                    "productId", prodId,
                    "quantity", 0
                ))))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void POST_stock_transfers_shouldReturn422_whenSourceEqualsDestination() throws Exception {
        when(executeTransferService.execute(any()))
            .thenThrow(new DomainException(ErrorCode.SAME_SOURCE_DESTINATION, "must differ"));

        mockMvc.perform(post("/api/v1/stock/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourceStoreId", srcId,
                    "destinationStoreId", srcId,
                    "productId", prodId,
                    "quantity", 1
                ))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.domainCode").value("SAME_SOURCE_DESTINATION"));
    }

    @Test
    void POST_stock_transfers_shouldReturn422_whenInsufficientStock() throws Exception {
        when(executeTransferService.execute(any()))
            .thenThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK, "not enough",
                Map.of("available", 5, "requested", 10)));

        mockMvc.perform(post("/api/v1/stock/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourceStoreId", srcId,
                    "destinationStoreId", destId,
                    "productId", prodId,
                    "quantity", 10
                ))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.domainCode").value("INSUFFICIENT_STOCK"))
            .andExpect(jsonPath("$.details.available").value(5));
    }

    @Test
    void GET_stock_transfers_history_shouldReturn200_withPagedResults() throws Exception {
        when(getTransferHistoryService.execute(any()))
            .thenReturn(new PageImpl<>(List.of(completedTransfer())));

        mockMvc.perform(get("/api/v1/stock/transfers")
                .param("page", "0").param("size", "25"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void GET_stock_transfers_history_shouldApply_sourceStoreFilter() throws Exception {
        when(getTransferHistoryService.execute(any()))
            .thenReturn(new PageImpl<>(List.of(completedTransfer())));

        mockMvc.perform(get("/api/v1/stock/transfers")
                .param("source", srcId.toString())
                .param("page", "0").param("size", "25"))
            .andExpect(status().isOk());
    }
}
