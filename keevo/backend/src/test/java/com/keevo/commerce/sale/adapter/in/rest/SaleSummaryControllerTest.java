package com.keevo.commerce.sale.adapter.in.rest;

import com.keevo.commerce.sale.adapter.in.SalesReadScope;
import com.keevo.commerce.sale.domain.port.out.SalesSummaryPort;
import com.keevo.commerce.sale.domain.port.out.SalesSummaryPort.Summary;
import com.keevo.shared.infrastructure.security.ShippedActorPermissions;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Story P03 — {@code GET /api/v1/sales/summary}: WAT day bounds and the {@link SalesReadScope} scope. */
@ExtendWith(MockitoExtension.class)
@DisplayName("P03 — GET /sales/summary")
class SaleSummaryControllerTest {

    @Mock private SalesSummaryPort port;

    private MockMvc mvc;
    private final UUID actor = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();

    /** 2026-11-27 00:00 WAT and 2026-11-29 00:00 WAT (from 27 to 28 inclusive). */
    private static final Instant FROM = Instant.parse("2026-11-26T23:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-28T23:00:00Z");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new SaleSummaryController(port, new SalesReadScope(ShippedActorPermissions.get())))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void as(String role) {
        ShippedActorPermissions.authenticate(actor, role, "OWNER".equals(role) ? null : store);
    }

    @Test
    @DisplayName("owner, every store: the period in WAT civil days, no store, no seller; figures returned")
    void ownerEveryStore() throws Exception {
        as("OWNER");
        when(port.summarize(FROM, TO, null, null)).thenReturn(new Summary(12_500, 3, 12_500));

        mvc.perform(get("/api/v1/sales/summary").param("from", "2026-11-27").param("to", "2026-11-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(12_500))
                .andExpect(jsonPath("$.data.count").value(3))
                .andExpect(jsonPath("$.data.paidAmount").value(12_500));
    }

    @Test
    @DisplayName("owner: store and seller filters honoured")
    void ownerFilters() throws Exception {
        as("OWNER");
        UUID seller = UUID.randomUUID();
        when(port.summarize(FROM, TO, store, seller)).thenReturn(Summary.NONE);

        mvc.perform(get("/api/v1/sales/summary").param("from", "2026-11-27").param("to", "2026-11-28")
                        .param("storeId", store.toString()).param("employeeId", seller.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(0));
    }

    @Test
    @DisplayName("seller: his store and his own sales, whatever he asks")
    void employeeOwnSales() throws Exception {
        as("EMPLOYEE");
        when(port.summarize(any(), any(), eq(store), eq(actor))).thenReturn(new Summary(4_000, 1, 4_000));

        mvc.perform(get("/api/v1/sales/summary").param("employeeId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    @DisplayName("manager (STORE_SALES_VIEW): his whole store")
    void managerWholeStore() throws Exception {
        as("MANAGER");
        when(port.summarize(any(), any(), eq(store), isNull())).thenReturn(Summary.NONE);

        mvc.perform(get("/api/v1/sales/summary")).andExpect(status().isOk());

        verify(port).summarize(any(), any(), eq(store), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE", "VIEWER"})
    @DisplayName("member asking another store: 403, nothing read")
    void otherStore(String role) throws Exception {
        as(role);

        mvc.perform(get("/api/v1/sales/summary").param("storeId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("inverted or malformed period: 422 with a French message, nothing read")
    void invalidPeriod() throws Exception {
        as("OWNER");

        mvc.perform(get("/api/v1/sales/summary").param("from", "2026-11-28").param("to", "2026-11-27"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/sales/summary").param("from", "28/11/2026"))
                .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(port);
    }
}
