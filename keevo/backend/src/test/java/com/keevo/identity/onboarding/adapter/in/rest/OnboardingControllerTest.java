package com.keevo.identity.onboarding.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.onboarding.adapter.in.rest.dto.OnboardingRequest;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingUseCase;
import com.keevo.identity.onboarding.domain.port.in.OnboardingResult;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OnboardingControllerTest — @WebMvcTest slice tests for the onboarding endpoint.
 *
 * <p>JwtAuthFilter is mocked as a pass-through so requests reach the controller.
 * The SecurityContext is populated manually to simulate an authenticated user (actorId as principal).
 */
@WebMvcTest(OnboardingController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("OnboardingController")
class OnboardingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean CompleteOnboardingUseCase completeOnboardingUseCase;
    @MockBean JwtAuthFilter jwtAuthFilter;

    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void setupJwtPassThrough() throws Exception {
        doAnswer(inv -> {
            // Populate the SecurityContext with a fake authenticated user
            var auth = new UsernamePasswordAuthenticationToken(
                ACTOR_ID, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("POST /onboarding/complete → 200 with OnboardingResponse for valid request")
    void should_return_200_with_onboarding_result_for_valid_request() throws Exception {
        when(completeOnboardingUseCase.complete(any()))
            .thenReturn(new OnboardingResult("KV-ABC123", SectorType.CLOTHING, "Boutique Céleste", 13));

        mockMvc.perform(post("/api/v1/onboarding/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new OnboardingRequest("CLOTHING", "Boutique Céleste"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sectorType").value("CLOTHING"))
            .andExpect(jsonPath("$.storeName").value("Boutique Céleste"))
            .andExpect(jsonPath("$.categoriesCreated").value(13));
    }

    @Test
    @DisplayName("POST /onboarding/complete → 401 when no JWT (Spring Security rejects unauthenticated request)")
    void should_return_401_when_no_jwt() throws Exception {
        // Reset filter to pass through WITHOUT setting SecurityContext authentication
        // Spring Security's authorization filter then sees no authentication and returns 401
        doAnswer(inv -> {
            SecurityContextHolder.clearContext(); // no authentication set
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());

        mockMvc.perform(post("/api/v1/onboarding/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sectorType\":\"CLOTHING\",\"storeName\":\"Test\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /onboarding/complete → 422 when storeName is blank")
    void should_return_422_when_store_name_is_blank() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sectorType\":\"CLOTHING\",\"storeName\":\"\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /onboarding/complete → 400 when sectorType is invalid/unknown")
    void should_return_400_for_invalid_sector_type() throws Exception {
        when(completeOnboardingUseCase.complete(any()))
            .thenThrow(new DomainException(ErrorCode.SECTOR_TEMPLATE_NOT_FOUND, "Unknown sector"));

        mockMvc.perform(post("/api/v1/onboarding/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sectorType\":\"UNKNOWN_SECTOR\",\"storeName\":\"Test\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.domainCode").value("SECTOR_TEMPLATE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /onboarding/complete → 422 when storeName is too short (1 char)")
    void should_return_422_when_store_name_too_short() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sectorType\":\"CLOTHING\",\"storeName\":\"A\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /onboarding/complete → 422 when sectorType is null")
    void should_return_422_when_sector_type_is_null() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeName\":\"Boutique Test\"}"))
            .andExpect(status().isUnprocessableEntity());
    }
}
