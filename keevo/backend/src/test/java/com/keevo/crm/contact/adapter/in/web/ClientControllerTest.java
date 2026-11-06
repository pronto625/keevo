package com.keevo.crm.contact.adapter.in.web;

import com.keevo.crm.contact.application.usecase.*;
import com.keevo.crm.contact.domain.entity.Client;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ClientController (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientController")
class ClientControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Mock CreateClientUseCase createClientUseCase;
    @Mock GetClientsUseCase getClientsUseCase;
    @Mock GetClientProfileUseCase getClientProfileUseCase;
    @Mock UpdateClientUseCase updateClientUseCase;
    @Mock ArchiveClientUseCase archiveClientUseCase;
    @Mock SetClientMarketingConsentUseCase consentUseCase;
    @Mock FindDuplicateClientsUseCase duplicatesUseCase;

    @InjectMocks ClientController clientController;

    MockMvc mockMvc;
    UUID actorId;
    Client sampleClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(clientController).build();

        actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId, null, List.of()));

        sampleClient = new Client(UUID.randomUUID(), "Alice", "+22670000001",
                "alice@mail.com", null, false, Instant.now(), Instant.now());
    }

    // ── POST /api/v1/clients ──────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/clients returns 201 with created client")
    void should_create_client() throws Exception {
        when(createClientUseCase.execute(any())).thenReturn(
                new CreateClientUseCase.CreateClientResult(sampleClient, List.of()));

        mockMvc.perform(post("/api/v1/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"Alice","phone":"+22670000001","email":"alice@mail.com"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Alice"));

        verify(createClientUseCase).execute(any());
    }

    // ── GET /api/v1/clients ───────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/clients returns list of clients")
    void should_list_clients() throws Exception {
        when(getClientsUseCase.execute(any())).thenReturn(List.of(sampleClient));

        mockMvc.perform(get("/api/v1/clients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Alice"));
    }

    // ── GET /api/v1/clients/{id} ──────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/clients/{id} returns client profile")
    void should_get_client_profile() throws Exception {
        UUID id = sampleClient.id();
        var result = new GetClientProfileUseCase.ClientProfileResult(sampleClient, 3L, 45000L);
        when(getClientProfileUseCase.execute(eq(id), any(), any())).thenReturn(result);

        mockMvc.perform(get("/api/v1/clients/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Alice"))
                .andExpect(jsonPath("$.data.purchaseCount").value(3));
    }

    @Test
    @DisplayName("GET /api/v1/clients/{id} returns 404 when not found")
    void should_return_404_for_missing_client() throws Exception {
        UUID id = UUID.randomUUID();
        when(getClientProfileUseCase.execute(eq(id), any(), any()))
                .thenThrow(new DomainException(ErrorCode.CLIENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/clients/" + id))
                .andExpect(status().isNotFound());
    }

    // ── PATCH /api/v1/clients/{id} ────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/clients/{id} returns updated client")
    void should_update_client() throws Exception {
        UUID id = sampleClient.id();
        when(updateClientUseCase.execute(any())).thenReturn(sampleClient);

        mockMvc.perform(patch("/api/v1/clients/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"Alice Updated"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Alice"));

        verify(updateClientUseCase).execute(any());
    }

    // ── DELETE /api/v1/clients/{id} ───────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/clients/{id} archives client and returns 204")
    void should_archive_client() throws Exception {
        UUID id = sampleClient.id();
        doNothing().when(archiveClientUseCase).execute(any(), any(), any(), any());

        mockMvc.perform(delete("/api/v1/clients/" + id))
                .andExpect(status().isNoContent());

        verify(archiveClientUseCase).execute(eq(id), eq(actorId), any(), any());
    }

    // ── Story 20.2 ────────────────────────────────────────────────

    @Test
    @DisplayName("POST with a same-store duplicate → 201 + meta.warnings DUPLICATE_PHONE with the existing client")
    void should_create_with_duplicate_warning() throws Exception {
        UUID store = UUID.randomUUID();
        Client existing = new Client(UUID.randomUUID(), "Alice Old", "+237699001122", null, null, false,
                Instant.now(), Instant.now(), null, store, null);
        when(createClientUseCase.execute(any())).thenReturn(
                new CreateClientUseCase.CreateClientResult(sampleClient, List.of(existing)));

        mockMvc.perform(post("/api/v1/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"Alice","phone":"699001122"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Alice"))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("DUPLICATE_PHONE"))
                .andExpect(jsonPath("$.meta.warnings[0].existingClient.id").value(existing.id().toString()))
                .andExpect(jsonPath("$.meta.warnings[0].existingClient.name").value("Alice Old"))
                .andExpect(jsonPath("$.meta.warnings[0].existingClient.phone").value("+237699001122"))
                .andExpect(jsonPath("$.meta.warnings[0].existingClient.storeId").value(store.toString()));
    }

    @Test
    @DisplayName("POST without duplicate → no meta; the V1 body still works and the response is a superset")
    void should_create_v1_body_without_meta() throws Exception {
        when(createClientUseCase.execute(any())).thenReturn(
                new CreateClientUseCase.CreateClientResult(sampleClient, List.of()));

        mockMvc.perform(post("/api/v1/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"name":"Alice","phone":"+22670000001","email":"alice@mail.com","notes":"x"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.phone").value("+22670000001"))
                .andExpect(jsonPath("$.data.email").value("alice@mail.com"))
                .andExpect(jsonPath("$.data.archived").value(false))
                .andExpect(jsonPath("$.data.marketingEligible").value(false))
                .andExpect(jsonPath("$.data.phoneNormalized").value("+22670000001"));
    }

    @Test
    @DisplayName("PUT /{id}/marketing-consent delegates with the consent flag")
    void should_set_marketing_consent() throws Exception {
        UUID id = sampleClient.id();
        when(consentUseCase.execute(eq(id), eq(true), eq(actorId), any(), any())).thenReturn(sampleClient);

        mockMvc.perform(put("/api/v1/clients/" + id + "/marketing-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"consent":true}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    @Test
    @DisplayName("GET /duplicates returns the matches of the store")
    void should_list_duplicates() throws Exception {
        when(duplicatesUseCase.execute(eq("699001122"), any(), any(), any())).thenReturn(List.of(sampleClient));

        mockMvc.perform(get("/api/v1/clients/duplicates").param("phone", "699001122"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Alice"));
    }
}
