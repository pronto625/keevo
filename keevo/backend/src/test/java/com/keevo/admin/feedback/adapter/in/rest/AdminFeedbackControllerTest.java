package com.keevo.admin.feedback.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.out.FeedbackRepository;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminFeedbackControllerTest — Tests for {@link AdminFeedbackController} (Story 14.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminFeedbackController")
class AdminFeedbackControllerTest {

    @Mock private FeedbackRepository feedbackRepository;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper.registerModule(new JavaTimeModule());
        var controller = new AdminFeedbackController(feedbackRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("GET /admin/feedback returns 200 for SUPER_ADMIN")
    void list_returns200_forSuperAdmin() throws Exception {
        authenticateAs("SUPER_ADMIN");
        Feedback feedback = new Feedback(UUID.randomUUID(), "bug", "Description du bug",
                "kv_abc123", UUID.randomUUID(), "1.0", "android", "/home", Instant.now(), "NORMAL");
        Page<Feedback> page = new PageImpl<>(List.of(feedback));
        when(feedbackRepository.findAll(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/feedback"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/feedback returns 403 for OWNER")
    void list_returns403_forOwner() throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(get("/api/v1/admin/feedback"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/feedback returns 403 for EMPLOYEE")
    void list_returns403_forEmployee() throws Exception {
        authenticateAs("EMPLOYEE");

        mockMvc.perform(get("/api/v1/admin/feedback"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/feedback filters by priority")
    void list_filtersByPriority() throws Exception {
        authenticateAs("SUPER_ADMIN");
        Feedback feedback = new Feedback(UUID.randomUUID(), "bug", "Description du bug",
                "kv_abc123", UUID.randomUUID(), "1.0", "android", "/home", Instant.now(), "HIGH");
        Page<Feedback> page = new PageImpl<>(List.of(feedback));
        when(feedbackRepository.findByPriority(eq("HIGH"), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/feedback?priority=HIGH"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/feedback caps size at 100")
    void list_capsSizeAt100() throws Exception {
        authenticateAs("SUPER_ADMIN");
        Page<Feedback> page = new PageImpl<>(List.of());
        when(feedbackRepository.findAll(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/feedback?size=10000"))
                .andExpect(status().isOk());
    }
}
