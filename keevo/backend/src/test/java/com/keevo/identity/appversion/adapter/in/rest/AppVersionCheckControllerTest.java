package com.keevo.identity.appversion.adapter.in.rest;

import com.keevo.identity.appversion.application.service.AppVersionCheckRateLimiter;
import com.keevo.identity.appversion.domain.model.VersionCheck;
import com.keevo.identity.appversion.domain.port.in.CheckAppVersionUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Story 19.10 — public version check at the HTTP edge: client IP for the limiter, 429, raw body, Cache-Control. */
@WebMvcTest(AppVersionCheckController.class)
@Import(com.keevo.shared.infrastructure.security.SecurityConfig.class)
@DisplayName("19.10 — AppVersionCheckController")
class AppVersionCheckControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean CheckAppVersionUseCase useCase;
    @MockitoBean AppVersionCheckRateLimiter rateLimiter;
    @MockitoBean JwtAuthFilter jwtAuthFilter;

    @BeforeEach
    void letFilterPassThrough() throws Exception {
        doAnswer(inv -> {
            FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
        when(useCase.check(any(), any(), any())).thenReturn(new VersionCheck(true, true, null, null, "2.1.0"));
    }

    @Test
    @DisplayName("200: raw body, Cache-Control public max-age=60; limiter fed the right-most X-Forwarded-For entry")
    void answersRawWithCacheControl() throws Exception {
        mockMvc.perform(get("/api/v1/app-version/check").param("platform", "android").param("version", "2.0.0")
                        .header("X-Forwarded-For", "1.1.1.1, 203.0.113.9"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.isForced").value(true))
                .andExpect(jsonPath("$.updateRequired").value(true))
                .andExpect(jsonPath("$.latestVersion").value("2.1.0"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(rateLimiter).acquire("203.0.113.9");
    }

    @Test
    @DisplayName("limiter refuses → 429 RATE_LIMIT_EXCEEDED, not cacheable, use case not called")
    void rateLimited() throws Exception {
        doThrow(new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED)).when(rateLimiter).acquire(anyString());
        mockMvc.perform(get("/api/v1/app-version/check").param("platform", "android").param("version", "2.0.0"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.domainCode").value("RATE_LIMIT_EXCEEDED"))
                // only Spring Security's default no-store header: never the public max-age of the 200
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("max-age=60"))));
        verify(useCase, never()).check(any(), any(), any());
    }
}
