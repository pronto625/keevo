package com.keevo.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityConfigAdminPathEnforcementTest — proves that the filter-chain
 * {@code hasRole("SUPER_ADMIN")} on {@code /api/v1/admin/**} blocks
 * non-SUPER_ADMIN roles <b>independently</b> of any per-method guard.
 *
 * <p>The stub controller below has NO {@code requireSuperAdmin()} call —
 * if the filter-chain did not enforce the role, OWNER would get 200.
 * With the rule in place, OWNER → 403 (from the filter-chain).
 *
 * <p>AC3: OWNER → 403 at filter-chain level (not via DomainException).
 * <p>AC4: SUPER_ADMIN → 200 (filter-chain lets it through).
 */
@WebMvcTest(controllers = SecurityConfigAdminPathEnforcementTest.StubAdminController.class)
@Import({SecurityConfig.class, SecurityConfigAdminPathEnforcementTest.StubAdminController.class})
@DisplayName("SecurityConfig — /api/v1/admin/** path-level enforcement")
class SecurityConfigAdminPathEnforcementTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtAuthFilter jwtAuthFilter;

    /**
     * Stub admin controller with NO per-method guard.
     * If the filter-chain does not block, OWNER gets 200 (RED without the rule).
     */
    @RestController
    static class StubAdminController {
        @GetMapping("/api/v1/admin/__test_only")
        ResponseEntity<Void> stub() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/v1/admin/__test_only")
        ResponseEntity<Void> stubPost() {
            return ResponseEntity.ok().build();
        }
    }

    @Test
    @DisplayName("OWNER on /api/v1/admin/** → 403 at filter-chain level (no per-method guard)")
    void ownerBlockedAtPathLevel() throws Exception {
        setupFilterAs("ROLE_OWNER");

        mockMvc.perform(get("/api/v1/admin/__test_only"))
                .andExpect(status().isForbidden())
                // Story 12.12: JSON body, not servlet HTML
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("SUPER_ADMIN on /api/v1/admin/** → 200")
    void superAdminAllowed() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        mockMvc.perform(get("/api/v1/admin/__test_only"))
                .andExpect(status().isOk());
    }

    // ── v1s-15-7 item 16: EMPLOYEE, anonymous, no-role token, non-GET ────────

    @Test
    @DisplayName("EMPLOYEE on /api/v1/admin/** → 403 JSON FORBIDDEN")
    void employeeBlockedAtPathLevel() throws Exception {
        setupFilterAs("ROLE_EMPLOYEE");

        mockMvc.perform(get("/api/v1/admin/__test_only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("anonymous on /api/v1/admin/** → 401 (HttpStatusEntryPoint)")
    void anonymousRejectedWith401() throws Exception {
        doAnswer(inv -> {
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());

        mockMvc.perform(get("/api/v1/admin/__test_only"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("authenticated token without any authority on /api/v1/admin/** → 403")
    void authenticatedWithoutAuthorityBlocked() throws Exception {
        setupFilterWith(List.of());

        mockMvc.perform(get("/api/v1/admin/__test_only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("OWNER POST on /api/v1/admin/** → 403 (rule covers non-GET methods)")
    void ownerPostBlockedAtPathLevel() throws Exception {
        setupFilterAs("ROLE_OWNER");

        mockMvc.perform(post("/api/v1/admin/__test_only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.domainCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("SUPER_ADMIN POST on /api/v1/admin/** → 200 (CSRF disabled, rule lets it through)")
    void superAdminPostAllowed() throws Exception {
        setupFilterAs("ROLE_SUPER_ADMIN");

        mockMvc.perform(post("/api/v1/admin/__test_only"))
                .andExpect(status().isOk());
    }

    private void setupFilterAs(String role) throws Exception {
        setupFilterWith(List.of(new SimpleGrantedAuthority(role)));
    }

    private void setupFilterWith(List<SimpleGrantedAuthority> authorities) throws Exception {
        doAnswer(inv -> {
            var auth = new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            inv.getArgument(2, FilterChain.class).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }
}