package com.keevo.store.store.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.api.StoreCategory;
import com.keevo.store.api.StoreProfilePatch;
import com.keevo.store.api.StoreType;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import com.keevo.store.store.domain.port.in.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * StoreControllerTest — Unit tests for StoreController (Story 3.1).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StoreController")
class StoreControllerTest {

    @Mock CreateStoreUseCase createStoreUseCase;
    @Mock UpdateStoreUseCase updateStoreUseCase;
    @Mock DeactivateStoreUseCase deactivateStoreUseCase;
    @Mock ListStoresUseCase listStoresUseCase;
    @Mock ReactivateStoreUseCase reactivateStoreUseCase;

    @InjectMocks StoreController storeController;

    MockMvc mockMvc;
    UUID actorId;
    UUID storeId;
    Store sampleStore;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(storeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        actorId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        authenticateAs("OWNER");
        sampleStore = new Store(storeId, "Boutique Test", StoreType.STORE,
                "Yaoundé", "+237690000001", true, Instant.now(), Instant.now());
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actorId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("POST /api/v1/stores returns 201 with created store")
    void POST_stores_shouldReturn201_withCreatedStore() throws Exception {
        when(createStoreUseCase.execute(any())).thenReturn(sampleStore);

        mockMvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Boutique Test","type":"STORE","address":"Yaoundé"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Boutique Test"))
                .andExpect(jsonPath("$.data.type").value("STORE"));
    }

    @Test
    @DisplayName("POST /api/v1/stores returns 403 when plan limit exceeded")
    void POST_stores_shouldReturn403_whenPlanLimitExceeded() throws Exception {
        when(createStoreUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "limit reached"));

        mockMvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"4th Store","type":"STORE"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/stores returns 409 when warehouse already exists")
    void POST_stores_shouldReturn409_whenWarehouseAlreadyExists() throws Exception {
        when(createStoreUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.WAREHOUSE_ALREADY_EXISTS, "already exists"));

        mockMvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Second WH","type":"WAREHOUSE"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PATCH /api/v1/stores/{id} returns 200 with updated store")
    void PATCH_stores_id_shouldReturn200_withUpdatedStore() throws Exception {
        Store updated = new Store(storeId, "Updated Name", StoreType.STORE,
                "New Address", null, true, Instant.now(), Instant.now());
        when(updateStoreUseCase.execute(any())).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/stores/" + storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated Name","address":"New Address"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated Name"));
    }

    // ── Story 19.12 — Bref fields ─────────────────────────────────────────────

    @Test
    @DisplayName("19.12 — PATCH with a V1 body sends an empty profile (nothing changes); the response is additive")
    void PATCH_v1Body_sendsNoProfile_responseHasBrefFields() throws Exception {
        Store updated = new Store(storeId, "Updated Name", StoreType.STORE, "New Address", null, true,
                Instant.now(), Instant.now(), StoreCategory.FOOD, null, storeId + "-19c0ffee.png", "M0123", null, "CM");
        when(updateStoreUseCase.execute(any())).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/stores/" + storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated Name","address":"New Address","phone":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("food"))
                .andExpect(jsonPath("$.data.niu").value("M0123"))
                .andExpect(jsonPath("$.data.country").value("CM"))
                .andExpect(jsonPath("$.data.logoUrl").value("/api/v1/stores/" + storeId + "/logo?v=19c0ffee"))
                .andExpect(jsonPath("$.data.isActive").value(true));
        verify(updateStoreUseCase).execute(new UpdateStoreCommand(storeId, "Updated Name", "New Address", null,
                actorId, StoreProfilePatch.NONE));
    }

    @Test
    @DisplayName("19.12 — PATCH carries the Bref fields raw to the use case")
    void PATCH_brefFields_passedToUseCase() throws Exception {
        when(updateStoreUseCase.execute(any())).thenReturn(sampleStore);

        mockMvc.perform(patch("/api/v1/stores/" + storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bo","category":"real_estate","country":"ci","niu":" M0123 ","description":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logoUrl").doesNotExist());
        verify(updateStoreUseCase).execute(new UpdateStoreCommand(storeId, "Bo", null, null, actorId,
                new StoreProfilePatch("real_estate", "", " M0123 ", null, "ci")));
    }

    @Test
    @DisplayName("19.12 — a bad Bref field answers VALIDATION_ERROR with its French message")
    void PATCH_badCategory_validationError() throws Exception {
        when(updateStoreUseCase.execute(any())).thenThrow(DomainException.userFacing(ErrorCode.VALIDATION_ERROR,
                "Catégorie de boutique inconnue"));

        mockMvc.perform(patch("/api/v1/stores/" + storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Boutique","category":"Alimentation"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error").value("Catégorie de boutique inconnue"));
    }

    @Test
    @DisplayName("19.12 — POST carries the optional Bref fields; omitted ⇒ empty profile")
    void POST_brefFields_passedToUseCase() throws Exception {
        when(createStoreUseCase.execute(any())).thenReturn(sampleStore);

        mockMvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Boutique","category":"food","rccm":"RC1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.country").value("CM"));
        verify(createStoreUseCase).execute(new CreateStoreCommand("Boutique", null, null, null, actorId,
                new StoreProfilePatch("food", null, null, "RC1", null)));
    }

    @Test
    @DisplayName("PATCH /api/v1/stores/{id}/deactivate returns 200 with inactive store")
    void PATCH_stores_id_deactivate_shouldReturn200_withInactiveStore() throws Exception {
        Store inactive = new Store(storeId, "Inactive Store", StoreType.STORE,
                null, null, false, Instant.now(), Instant.now());
        when(deactivateStoreUseCase.execute(any())).thenReturn(inactive);

        mockMvc.perform(patch("/api/v1/stores/" + storeId + "/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    @Test
    @DisplayName("J7 — PATCH /api/v1/stores/{id}/reactivate returns 200 with active store")
    void PATCH_stores_id_reactivate_shouldReturn200_withActiveStore() throws Exception {
        when(reactivateStoreUseCase.execute(any())).thenReturn(sampleStore);

        mockMvc.perform(patch("/api/v1/stores/" + storeId + "/reactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true));
        verify(reactivateStoreUseCase).execute(new ReactivateStoreCommand(storeId, actorId));
    }

    @Test
    @DisplayName("J7 — PATCH /reactivate returns 403 when the plan store limit is reached")
    void PATCH_stores_id_reactivate_shouldReturn403_whenPlanLimitExceeded() throws Exception {
        when(reactivateStoreUseCase.execute(any()))
                .thenThrow(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "limit reached"));

        mockMvc.perform(patch("/api/v1/stores/" + storeId + "/reactivate"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("J7 — PATCH /reactivate is refused to an EMPLOYEE")
    void PATCH_stores_id_reactivate_employee_forbidden() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(patch("/api/v1/stores/" + storeId + "/reactivate"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reactivateStoreUseCase);
    }

    @Test
    @DisplayName("GET /api/v1/stores returns 200 with store list")
    void GET_stores_shouldReturn200_withStoreList() throws Exception {
        when(listStoresUseCase.execute(any())).thenReturn(List.of(sampleStore));

        mockMvc.perform(get("/api/v1/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("Boutique Test"));
    }

    @Test
    @DisplayName("POST /api/v1/stores — no auth context returns 403 via requireOwner guard")
    void POST_stores_withoutAuthContext_returns403() throws Exception {
        // requireOwner() guard now catches null/empty auth before reaching any use case.
        SecurityContextHolder.clearContext();
        mockMvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Unauthorized Store","type":"STORE"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ── Story 12.6 — EMPLOYEE 403 on all store management endpoints ────

    @Test
    @DisplayName("POST /api/v1/stores — EMPLOYEE forbidden")
    void POST_stores_employeeForbidden_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(post("/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Employee Store","type":"STORE"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/stores — EMPLOYEE forbidden")
    void GET_stores_employeeForbidden_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(get("/api/v1/stores"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/stores/{id} — EMPLOYEE forbidden")
    void PATCH_stores_employeeForbidden_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(patch("/api/v1/stores/" + storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Updated Name"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/stores/{id}/deactivate — EMPLOYEE forbidden")
    void PATCH_stores_deactivate_employeeForbidden_shouldReturn403() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(patch("/api/v1/stores/" + storeId + "/deactivate"))
                .andExpect(status().isForbidden());
    }
}
