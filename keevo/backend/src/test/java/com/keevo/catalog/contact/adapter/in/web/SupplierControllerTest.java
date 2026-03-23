package com.keevo.catalog.contact.adapter.in.web;

import com.keevo.catalog.contact.application.usecase.*;
import com.keevo.catalog.contact.domain.entity.Supplier;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for SupplierController (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupplierController")
class SupplierControllerTest {

    @Mock CreateSupplierUseCase createSupplierUseCase;
    @Mock GetSuppliersUseCase getSuppliersUseCase;
    @Mock GetSupplierProfileUseCase getSupplierProfileUseCase;
    @Mock UpdateSupplierUseCase updateSupplierUseCase;
    @Mock ArchiveSupplierUseCase archiveSupplierUseCase;

    @InjectMocks SupplierController supplierController;

    MockMvc mockMvc;
    UUID actorId;
    Supplier sampleSupplier;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(supplierController).build();

        actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null, List.of()));

        sampleSupplier = new Supplier(UUID.randomUUID(), "Acme Co", "+22670000010",
                null, false, Instant.now(), Instant.now());
    }

    // ── POST /api/v1/suppliers ────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/suppliers returns 201 with created supplier")
    void should_create_supplier() throws Exception {
        when(createSupplierUseCase.execute(any())).thenReturn(sampleSupplier);

        mockMvc.perform(post("/api/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"Acme Co","phone":"+22670000010"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Acme Co"));

        verify(createSupplierUseCase).execute(any());
    }

    // ── GET /api/v1/suppliers ─────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/suppliers returns list of suppliers")
    void should_list_suppliers() throws Exception {
        when(getSuppliersUseCase.execute(any())).thenReturn(List.of(sampleSupplier));
        when(getSupplierProfileUseCase.execute(sampleSupplier.id()))
                .thenReturn(new GetSupplierProfileUseCase.SupplierProfileResult(sampleSupplier, List.of()));

        mockMvc.perform(get("/api/v1/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Acme Co"));
    }

    // ── GET /api/v1/suppliers/{id} ────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/suppliers/{id} returns supplier profile with product IDs")
    void should_get_supplier_profile() throws Exception {
        UUID id = sampleSupplier.id();
        UUID p1 = UUID.randomUUID();
        var result = new GetSupplierProfileUseCase.SupplierProfileResult(sampleSupplier, List.of(p1));
        when(getSupplierProfileUseCase.execute(id)).thenReturn(result);

        mockMvc.perform(get("/api/v1/suppliers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Acme Co"))
                .andExpect(jsonPath("$.data.productIds[0]").value(p1.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/suppliers/{id} returns 404 when not found")
    void should_return_404_for_missing_supplier() throws Exception {
        UUID id = UUID.randomUUID();
        when(getSupplierProfileUseCase.execute(id))
                .thenThrow(new DomainException(ErrorCode.SUPPLIER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/suppliers/" + id))
                .andExpect(status().isNotFound());
    }

    // ── PATCH /api/v1/suppliers/{id} ──────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/suppliers/{id} returns updated supplier")
    void should_update_supplier() throws Exception {
        UUID id = sampleSupplier.id();
        when(updateSupplierUseCase.execute(any())).thenReturn(sampleSupplier);
        when(getSupplierProfileUseCase.execute(id))
                .thenReturn(new GetSupplierProfileUseCase.SupplierProfileResult(sampleSupplier, List.of()));

        mockMvc.perform(patch("/api/v1/suppliers/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"Acme Updated"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Acme Co"));

        verify(updateSupplierUseCase).execute(any());
    }

    // ── DELETE /api/v1/suppliers/{id} ─────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/suppliers/{id} archives supplier and returns 204")
    void should_archive_supplier() throws Exception {
        UUID id = sampleSupplier.id();
        doNothing().when(archiveSupplierUseCase).execute(any(), any(), any());

        mockMvc.perform(delete("/api/v1/suppliers/" + id))
                .andExpect(status().isNoContent());

        verify(archiveSupplierUseCase).execute(eq(id), eq(actorId), any());
    }
}
