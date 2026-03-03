package com.keevo.identity.auth.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.domain.port.in.RegisterUserCommand;
import com.keevo.identity.auth.domain.port.in.RegisterUserUseCase;
import com.keevo.identity.auth.domain.port.in.RegistrationResult;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean RegisterUserUseCase registerUserUseCase;

    private static final String URL = "/api/v1/auth/register";

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /register → 201 Created with tenantCode and token")
    void register_returns201OnSuccess() throws Exception {
        RegistrationResult result = new RegistrationResult(
                "KV-ABC123", "STUB:user-id:tenant-id", "user-id", "tenant-id");

        when(registerUserUseCase.register(any(RegisterUserCommand.class))).thenReturn(result);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("+237600000001", "SecurePass123!"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantCode").value("KV-ABC123"))
                .andExpect(jsonPath("$.token").value("STUB:user-id:tenant-id"));
    }

    // ── AC3: duplicate phone → 409 ──────────────────────────────────────────

    @Test
    @DisplayName("POST /register → 409 Conflict when phone already registered")
    void register_returns409WhenPhoneTaken() throws Exception {
        when(registerUserUseCase.register(any(RegisterUserCommand.class)))
                .thenThrow(new DomainException(ErrorCode.USER_ALREADY_EXISTS));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("+237600000001", "SecurePass123!"))))
                .andExpect(status().isConflict())
                // H1 RED: AC3 requires domainCode in body — asserting it explicitly
                .andExpect(jsonPath("$.domainCode").value("USER_ALREADY_EXISTS"));
    }

    // ── Bean validation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /register → 422 Unprocessable Entity when phone is blank")
    void register_returns400WhenPhoneBlank() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("", "SecurePass123!"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /register → 422 Unprocessable Entity when password is too short")
    void register_returns400WhenPasswordTooShort() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistrationRequest("+237600000001", "short"))))
                .andExpect(status().isUnprocessableEntity());
    }
}
