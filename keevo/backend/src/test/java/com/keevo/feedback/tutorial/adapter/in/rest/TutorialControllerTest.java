package com.keevo.feedback.tutorial.adapter.in.rest;

import com.keevo.feedback.tutorial.domain.model.Tutorial;
import com.keevo.feedback.tutorial.domain.port.in.ListTutorialsUseCase;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1s-14-6 — tutorial catalog behind the real {@link SecurityConfig}: OWNER and EMPLOYEE read it,
 * anonymous → 401.
 */
@WebMvcTest(TutorialController.class)
@Import(SecurityConfig.class)
@DisplayName("TutorialController (v1s-14-6, FR7)")
class TutorialControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ListTutorialsUseCase useCase;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("EMPLOYEE → 200 with the catalog in the envelope")
    void employeeReadsCatalog() throws Exception {
        filterAs("ROLE_EMPLOYEE");
        when(useCase.list()).thenReturn(List.of(
                new Tutorial("vendre", "Vendre", "Enregistrer une vente", "d", 120, "https://youtu.be/x"),
                new Tutorial("stock", "Catalogue & stock", "Stock", "d", 110, "")));

        mockMvc.perform(get("/api/v1/help/tutorials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("vendre"))
                .andExpect(jsonPath("$.data[0].module").value("Vendre"))
                .andExpect(jsonPath("$.data[0].durationSeconds").value(120))
                .andExpect(jsonPath("$.data[0].url").value("https://youtu.be/x"))
                .andExpect(jsonPath("$.data[1].url").value(""));
    }

    @Test
    @DisplayName("OWNER → 200")
    void ownerReadsCatalog() throws Exception {
        filterAs("ROLE_OWNER");
        when(useCase.list()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/help/tutorials")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("anonymous → 401")
    void anonymousRejected() throws Exception {
        filterAsAnonymous();
        mockMvc.perform(get("/api/v1/help/tutorials")).andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }

    private void filterAs(String authority) throws Exception {
        doAnswer(inv -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority(authority))));
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    private void filterAsAnonymous() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}
