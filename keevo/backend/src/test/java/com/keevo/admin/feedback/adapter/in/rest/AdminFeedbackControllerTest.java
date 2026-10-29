package com.keevo.admin.feedback.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService;
import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService.FeedbackLabels;
import com.keevo.feedback.feedback.adapter.in.api.FeedbackApiTestSupport;
import com.keevo.feedback.feedback.domain.model.Feedback;
import com.keevo.feedback.feedback.domain.port.in.HandleFeedbackUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase;
import com.keevo.feedback.feedback.domain.port.in.ListFeedbackUseCase.ListFeedbackQuery;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminFeedbackControllerTest — Tests for {@link AdminFeedbackController} (Story 14.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminFeedbackController")
class AdminFeedbackControllerTest {

    @Mock private ListFeedbackUseCase listFeedbackUseCase;
    @Mock private HandleFeedbackUseCase handleFeedbackUseCase;
    @Mock private AdminFeedbackLabelService labelService;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper.registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(securedController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        SecurityContextHolder.clearContext();
        lenient().when(labelService.labels(anyCollection())).thenReturn(java.util.Map.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * standaloneSetup mounts no method security, so the class-level {@code @PreAuthorize} would be
     * ignored. Wrap the controller in the same AOP interceptor {@code @EnableMethodSecurity}
     * registers: a denied call throws {@code AuthorizationDeniedException}, which
     * {@link GlobalExceptionHandler} maps to 403.
     */
    private Object securedController() {
        ProxyFactory factory = new ProxyFactory(new AdminFeedbackController(
                FeedbackApiTestSupport.inboxPort(listFeedbackUseCase, handleFeedbackUseCase), labelService));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return factory.getProxy();
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
        Page<Feedback> page = new PageImpl<>(List.of(feedback), PageRequest.of(0, 20), 1);
        when(listFeedbackUseCase.list(new ListFeedbackQuery(0, 20, null, null, null, false))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("bug"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
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
        Page<Feedback> page = new PageImpl<>(List.of(feedback), PageRequest.of(0, 20), 1);
        when(listFeedbackUseCase.list(new ListFeedbackQuery(0, 20, "HIGH", null, null, false))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/feedback?priority=HIGH"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/feedback forwards paging and filters to the use case (cap lives in ListFeedbackService)")
    void list_forwardsQueryToUseCase() throws Exception {
        authenticateAs("SUPER_ADMIN");
        Page<Feedback> page = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(listFeedbackUseCase.list(new ListFeedbackQuery(0, 10000, null, "/pos", null, false))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/feedback?size=10000&screenContext=/pos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(100));
    }

    // ── Spec 9-1 completion ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/feedback adds tenantName + userLabel and the handled flag")
    void list_addsLabels() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        Feedback feedback = new Feedback(id, "BUG", "Le scanner plante", "kv_abc123", UUID.randomUUID(),
                "1.4.2", "android", "/pos", Instant.parse("2026-10-12T09:00:00Z"), "HIGH");
        when(listFeedbackUseCase.list(new ListFeedbackQuery(0, 20, null, null, "BUG", null)))
                .thenReturn(new PageImpl<>(List.of(feedback), PageRequest.of(0, 20), 1));
        when(labelService.labels(List.of(FeedbackApiTestSupport.view(feedback))))
                .thenReturn(java.util.Map.of(id, new FeedbackLabels("Boutique Awa", "Awa Diallo")));

        mockMvc.perform(get("/api/v1/admin/feedback?type=BUG&handled=all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tenantName").value("Boutique Awa"))
                .andExpect(jsonPath("$.data[0].userLabel").value("Awa Diallo"))
                .andExpect(jsonPath("$.data[0].handled").value(false))
                .andExpect(jsonPath("$.data[0].appVersion").value("1.4.2"))
                .andExpect(jsonPath("$.data[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());
    }

    @Test
    @DisplayName("GET /admin/feedback?handled=true lists handled feedback only")
    void list_handledTrue() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(listFeedbackUseCase.list(new ListFeedbackQuery(0, 20, null, null, null, true)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/admin/feedback?handled=true"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/feedback with an invalid priority or handled value → 422 VALIDATION_ERROR")
    void list_invalidParams_422() throws Exception {
        authenticateAs("SUPER_ADMIN");

        mockMvc.perform(get("/api/v1/admin/feedback?priority=URGENT"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/admin/feedback?handled=maybe"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/admin/feedback?type=RANT"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/admin/feedback").param("screenContext", "/x".repeat(101)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.domainCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(listFeedbackUseCase);
    }

    @Test
    @DisplayName("POST /admin/feedback/{id}/handle marks it handled by the admin")
    void handle_marksHandled() throws Exception {
        UUID admin = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        when(handleFeedbackUseCase.markHandled(id, admin)).thenReturn(new Feedback(id, "BUG", "Le scanner plante",
                "kv_abc123", UUID.randomUUID(), null, null, null, now, "HIGH", now, admin));

        mockMvc.perform(post("/api/v1/admin/feedback/{id}/handle", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handled").value(true))
                .andExpect(jsonPath("$.data.tenantName").value("Entreprise supprimée"));
    }

    @Test
    @DisplayName("GET /admin/feedback/count returns only the count (no labels, no rows)")
    void count_returnsCountOnly() throws Exception {
        authenticateAs("SUPER_ADMIN");
        when(listFeedbackUseCase.count(new ListFeedbackQuery(0, 1, "HIGH", null, null, false))).thenReturn(3L);

        mockMvc.perform(get("/api/v1/admin/feedback/count?priority=HIGH&handled=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));
        verifyNoInteractions(labelService);
        verify(listFeedbackUseCase, never()).list(any());
    }

    @Test
    @DisplayName("GET /admin/feedback/count as OWNER → 403")
    void count_owner_403() throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(get("/api/v1/admin/feedback/count"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(listFeedbackUseCase);
    }

    @Test
    @DisplayName("POST /admin/feedback/{id}/handle — labels failing after the commit → 200 with fallback labels")
    void handle_labelFailure_fallsBack() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        when(handleFeedbackUseCase.markHandled(eq(id), any())).thenReturn(new Feedback(id, "BUG", "Le scanner plante",
                "kv_abc123", UUID.randomUUID(), null, null, null, now, "HIGH", now, UUID.randomUUID()));
        when(labelService.labels(anyCollection()))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("pool exhausted"));

        mockMvc.perform(post("/api/v1/admin/feedback/{id}/handle", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handled").value(true))
                .andExpect(jsonPath("$.data.tenantName").value("Entreprise supprimée"))
                .andExpect(jsonPath("$.data.userLabel").value("Utilisateur inconnu"));
    }

    @Test
    @DisplayName("POST /admin/feedback/{id}/unhandle puts it back in the inbox")
    void unhandle_clearsFlag() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        when(handleFeedbackUseCase.markUnhandled(eq(id), any())).thenReturn(new Feedback(id, "BUG", "Le scanner plante",
                "kv_abc123", UUID.randomUUID(), null, null, null, Instant.now(), "HIGH"));

        mockMvc.perform(post("/api/v1/admin/feedback/{id}/unhandle", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handled").value(false));
    }

    @Test
    @DisplayName("POST /admin/feedback/{id}/handle with an unknown id → 404 NOT_FOUND")
    void handle_unknownId_404() throws Exception {
        authenticateAs("SUPER_ADMIN");
        UUID id = UUID.randomUUID();
        when(handleFeedbackUseCase.markHandled(eq(id), any()))
                .thenThrow(new DomainException(ErrorCode.NOT_FOUND, "Feedback not found"));

        mockMvc.perform(post("/api/v1/admin/feedback/{id}/handle", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.domainCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /admin/feedback/{id}/handle as OWNER → 403, nothing changed")
    void handle_owner_403() throws Exception {
        authenticateAs("OWNER");

        mockMvc.perform(post("/api/v1/admin/feedback/{id}/handle", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verify(handleFeedbackUseCase, never()).markHandled(any(), any());
    }
}
