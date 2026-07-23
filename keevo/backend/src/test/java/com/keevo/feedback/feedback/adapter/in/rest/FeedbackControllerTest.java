package com.keevo.feedback.feedback.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.SubmitFeedbackUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FeedbackControllerTest — Tests for {@link FeedbackController} (Story 14.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackController")
class FeedbackControllerTest {

    @Mock private SubmitFeedbackUseCase submitFeedbackUseCase;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private UUID userId;
    private String tenantId;

    @BeforeEach
    void setUp() {
        mapper.registerModule(new JavaTimeModule());
        var controller = new FeedbackController(submitFeedbackUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        userId = UUID.randomUUID();
        tenantId = "kv_abc123";

        SecurityContextHolder.clearContext();
        TenantContext.setCurrentTenant(tenantId);
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("POST /feedback returns 200 for OWNER")
    void submit_returns200_forOwner() throws Exception {
        authenticateAs("OWNER");
        Feedback feedback = new Feedback(UUID.randomUUID(), "bug", "Description du bug",
                tenantId, userId, "1.0", "android", "/home", Instant.now(), "NORMAL");
        when(submitFeedbackUseCase.submit(any())).thenReturn(feedback);

        String body = """
                {"type":"bug","description":"Description du bug","appVersion":"1.0","platform":"android","screenContext":"/home"}
                """;

        mockMvc.perform(post("/api/v1/feedback")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /feedback returns 200 for EMPLOYEE")
    void submit_returns200_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");
        Feedback feedback = new Feedback(UUID.randomUUID(), "suggestion", "Une suggestion",
                tenantId, userId, "1.0", "ios", "/settings", Instant.now(), "NORMAL");
        when(submitFeedbackUseCase.submit(any())).thenReturn(feedback);

        String body = """
                {"type":"suggestion","description":"Une suggestion","appVersion":"1.0","platform":"ios","screenContext":"/settings"}
                """;

        mockMvc.perform(post("/api/v1/feedback")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /feedback returns 400 when description too short")
    void submit_returns400_whenDescriptionTooShort() throws Exception {
        authenticateAs("OWNER");

        String body = """
                {"type":"bug","description":"trop court","appVersion":"1.0","platform":"android","screenContext":"/home"}
                """;

        mockMvc.perform(post("/api/v1/feedback")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /feedback returns 400 when type is blank")
    void submit_returns400_whenTypeBlank() throws Exception {
        authenticateAs("OWNER");

        String body = """
                {"type":"","description":"Description du bug","appVersion":"1.0","platform":"android","screenContext":"/home"}
                """;

        mockMvc.perform(post("/api/v1/feedback")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
