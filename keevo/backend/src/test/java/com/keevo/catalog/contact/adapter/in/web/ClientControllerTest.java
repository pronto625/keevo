package com.keevo.catalog.contact.adapter.in.web;

import com.keevo.catalog.contact.application.usecase.*;
import com.keevo.catalog.contact.domain.entity.Client;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ClientController (Story 2.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientController")
class ClientControllerTest {

    @Mock CreateClientUseCase createClientUseCase;
    @Mock GetClientsUseCase getClientsUseCase;
    @Mock GetClientProfileUseCase getClientProfileUseCase;
    @Mock UpdateClientUseCase updateClientUseCase;
    @Mock ArchiveClientUseCase archiveClientUseCase;

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
        when(createClientUseCase.execute(any())).thenReturn(sampleClient);

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
        when(getClientProfileUseCase.execute(id)).thenReturn(result);

        mockMvc.perform(get("/api/v1/clients/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Alice"))
                .andExpect(jsonPath("$.data.purchaseCount").value(3));
    }

    @Test
    @DisplayName("GET /api/v1/clients/{id} returns 404 when not found")
    void should_return_404_for_missing_client() throws Exception {
        UUID id = UUID.randomUUID();
        when(getClientProfileUseCase.execute(id))
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
        doNothing().when(archiveClientUseCase).execute(any(), any(), any());

        mockMvc.perform(delete("/api/v1/clients/" + id))
                .andExpect(status().isNoContent());

        verify(archiveClientUseCase).execute(eq(id), eq(actorId), any());
    }
}
