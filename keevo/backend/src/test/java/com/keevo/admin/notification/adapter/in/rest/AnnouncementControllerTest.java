package com.keevo.admin.notification.adapter.in.rest;

import com.keevo.admin.notification.domain.model.Announcement;
import com.keevo.admin.notification.domain.port.in.ListAnnouncementsUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementController (Story 9-5 — app announcements)")
class AnnouncementControllerTest {

    @Mock ListAnnouncementsUseCase listAnnouncements;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AnnouncementController(listAnnouncements))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        TenantContext.setCurrentTenant("kv_aaaaaa");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void owner_getsTheAnnouncementsOfItsCompany() throws Exception {
        authenticateAs("OWNER");
        UUID id = UUID.randomUUID();
        when(listAnnouncements.announcements("kv_aaaaaa", "2026-10-01T00:00:00Z")).thenReturn(List.of(
                new Announcement(id, "Maintenance", "Ce soir", Instant.parse("2026-10-13T10:00:30Z"))));

        mockMvc.perform(get("/api/v1/notifications/announcements").param("since", "2026-10-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].title").value("Maintenance"))
                .andExpect(jsonPath("$.data[0].body").value("Ce soir"))
                .andExpect(jsonPath("$.data[0].sentAt").value("2026-10-13T10:00:30Z"));
    }

    @Test
    void employee_allowed_tenantFromContextOnly() throws Exception {
        authenticateAs("EMPLOYEE");
        when(listAnnouncements.announcements("kv_aaaaaa", null)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/notifications/announcements").param("tenant", "kv_other1"))
                .andExpect(status().isOk());
        verify(listAnnouncements).announcements("kv_aaaaaa", null);
    }

    @Test
    void superAdmin_forbidden() throws Exception {
        authenticateAs("SUPER_ADMIN");
        mockMvc.perform(get("/api/v1/notifications/announcements"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
        verifyNoInteractions(listAnnouncements);
    }
}
