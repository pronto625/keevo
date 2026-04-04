package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.domain.port.in.DeleteDeviceTokenUseCase;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenUseCase;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class DeviceTokenControllerTest {

    @Mock
    private RegisterDeviceTokenUseCase registerUseCase;

    @Mock
    private DeleteDeviceTokenUseCase deleteUseCase;

    @InjectMocks
    private DeviceTokenController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void setAuth(String role) {
        UUID userId = UUID.randomUUID();
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void postToken_owner_returns200() throws Exception {
        setAuth("OWNER");

        mockMvc.perform(post("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-tok\",\"platform\":\"ANDROID\",\"deviceName\":\"Test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registered").value(true));

        verify(registerUseCase).register(any());
    }

    @Test
    void postToken_employee_returns200() throws Exception {
        setAuth("EMPLOYEE");

        mockMvc.perform(post("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-tok\",\"platform\":\"IOS\",\"deviceName\":\"iPhone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registered").value(true));
    }

    @Test
    void postToken_blankToken_returns400() throws Exception {
        setAuth("OWNER");

        mockMvc.perform(post("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\",\"platform\":\"ANDROID\",\"deviceName\":\"Test\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteToken_validToken_returns200WithDeletedTrue() throws Exception {
        setAuth("OWNER");
        when(deleteUseCase.delete("fcm-token-to-delete")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-to-delete\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void deleteToken_nonExistentToken_returns200WithDeletedFalse() throws Exception {
        setAuth("OWNER");
        when(deleteUseCase.delete("non-existent")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/devices/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"non-existent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(false));
    }
}
