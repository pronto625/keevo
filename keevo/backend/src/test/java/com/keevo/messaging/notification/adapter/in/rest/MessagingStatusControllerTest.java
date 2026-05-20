package com.keevo.messaging.notification.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MessagingStatusControllerTest {

    @Mock
    private WhatsAppPort whatsAppPort;

    private MessagingStatusController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(whatsAppPort.isConfigured()).thenReturn(false);
        controller = new MessagingStatusController("noop", false, whatsAppPort);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void setAuth(String role) {
        UUID userId = UUID.randomUUID();
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void getStatus_returns200WithProviderAndFcmFields() throws Exception {
        setAuth("OWNER");

        mockMvc.perform(get("/api/v1/messaging/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.whatsappProvider").exists())
                .andExpect(jsonPath("$.data.fcmEnabled").exists());
    }
}
