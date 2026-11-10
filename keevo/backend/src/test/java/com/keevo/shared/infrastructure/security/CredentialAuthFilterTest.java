package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.access.AccessState;
import com.keevo.shared.infrastructure.security.access.AccessStateService;
import com.keevo.shared.module.ModuleProvisioner;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("21.9 — credential filter: owner session bound for the request only, JWT checks applied, refusals")
class CredentialAuthFilterTest {

    static final String SCHEMA = "kv_abc123";
    static final UUID OWNER = UUID.randomUUID();
    static final Instant CREATED = Instant.parse("2026-11-10T08:00:00Z");

    private AccessStateService accessStates;
    private ModuleProvisioner provisioner;
    private CredentialAuthenticator.Refusal after;
    private CredentialAuthFilter filter;

    @BeforeEach
    void setUp() {
        accessStates = mock(AccessStateService.class);
        after = null;
        CredentialAuthenticator authenticator = new CredentialAuthenticator() {
            @Override
            public boolean handles(HttpServletRequest request) {
                return "/api/v1/mcp".equals(request.getRequestURI());
            }

            @Override
            public Resolution resolve(HttpServletRequest request) {
                return "good".equals(request.getHeader("Authorization"))
                        ? new Owner(OWNER, SCHEMA, CREATED)
                        : Refusal.of(401, "MCP_TOKEN_INVALID");
            }

            @Override
            public String revokedCode() {
                return "MCP_TOKEN_INVALID";
            }

            @Override
            public Refusal afterAuthentication(HttpServletRequest request, Owner owner) {
                assertThat(TenantContext.getCurrentTenant()).isEqualTo(SCHEMA);
                return after;
            }
        };
        provisioner = mock(ModuleProvisioner.class);
        filter = new CredentialAuthFilter(List.of(authenticator), accessStates, provisioner, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static AccessState state(String role, boolean active, String status, Instant tva) {
        return new AccessState(role, active, status, tva,
                new AccessState.EmployeeState(null, "ACTIVE", false, "Awa"), Set.of());
    }

    private MockHttpServletResponse run(String path, String credential, AtomicReference<Authentication> seen,
                                        AtomicReference<String> tenant) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (credential != null) {
            request.addHeader("Authorization", credential);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            tenant.set(TenantContext.getCurrentTenant());
        });
        assertThat(TenantContext.getCurrentTenant()).as("tenant cleared").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("security cleared").isNull();
        assertThat(MDC.get("tenantId")).isNull();
        if (path.equals("/api/v1/mcp")) {
            assertThat(request.getAttribute(JwtAuthFilter.ALTERNATE_CREDENTIAL_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        }
        return response;
    }

    @Test
    @DisplayName("owner: principal, ROLE_OWNER, details and tenant bound inside the chain, cleared after")
    void binds() throws Exception {
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", true, "ACTIVE", CREATED.minusSeconds(60)));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        MockHttpServletResponse r = run("/api/v1/mcp", "good", seen, tenant);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(tenant.get()).isEqualTo(SCHEMA);
        assertThat(seen.get().getPrincipal()).isEqualTo(OWNER);
        assertThat(seen.get().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OWNER");
        assertThat(((AuthDetails) seen.get().getDetails()).firstName()).isEqualTo("Awa");
        assertThat(((AuthDetails) seen.get().getDetails()).storeId()).isNull();
    }

    @Test
    @DisplayName("another path is left to the JWT filter (not marked, nothing bound)")
    void otherPath() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        assertThat(run("/api/v1/products", null, seen, tenant).getStatus()).isEqualTo(200);
        assertThat(tenant.get()).isNull();
    }

    @Test
    @DisplayName("refusals: bad credential, no membership, deleted company, inactive, no longer owner, revoked, after-hook")
    void refusals() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();

        MockHttpServletResponse bad = run("/api/v1/mcp", "bad", seen, tenant);
        assertThat(bad.getStatus()).isEqualTo(401);
        assertThat(bad.getContentAsString()).contains("MCP_TOKEN_INVALID");

        when(accessStates.get(OWNER, SCHEMA)).thenReturn(null);
        assertThat(run("/api/v1/mcp", "good", seen, tenant).getContentAsString()).contains("ACCOUNT_INACTIVE");
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", true, "DELETED", null));
        assertThat(run("/api/v1/mcp", "good", seen, tenant).getContentAsString()).contains("ACCOUNT_INACTIVE");
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", false, "ACTIVE", null));
        assertThat(run("/api/v1/mcp", "good", seen, tenant).getContentAsString()).contains("ACCOUNT_INACTIVE");
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("MANAGER", true, "ACTIVE", null));
        MockHttpServletResponse demoted = run("/api/v1/mcp", "good", seen, tenant);
        assertThat(demoted.getStatus()).isEqualTo(401);
        assertThat(demoted.getContentAsString()).contains("MCP_TOKEN_INVALID");
        assertThat(demoted.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", true, "ACTIVE", CREATED.plusSeconds(1)));
        assertThat(run("/api/v1/mcp", "good", seen, tenant).getContentAsString()).contains("MCP_TOKEN_INVALID");

        // 21.4.2: a suspended company (or one being deleted) is refused like every write of the JWT path
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", true, "SUSPENDED", null));
        MockHttpServletResponse suspended = run("/api/v1/mcp", "good", seen, tenant);
        assertThat(suspended.getStatus()).isEqualTo(403);
        assertThat(suspended.getContentAsString()).contains("ACCOUNT_SUSPENDED").contains("\"code\":\"FORBIDDEN\"");
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", true, "DELETION_PENDING", null));
        assertThat(run("/api/v1/mcp", "good", seen, tenant).getContentAsString()).contains("ACCOUNT_DELETION_PENDING");

        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", true, "ACTIVE", null));
        after = new CredentialAuthenticator.Refusal(429, "RATE_LIMIT_EXCEEDED", null,
                java.util.Map.of("Retry-After", "60"));
        MockHttpServletResponse limited = run("/api/v1/mcp", "good", seen, tenant);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("Trop de requêtes");
        assertThat(tenant.get()).as("chain never reached when refused").isNull();
    }

    @Test
    @DisplayName("infrastructure failures: database → 500 INTERNAL_ERROR, foundation not ready → 503 + Retry-After, JSON")
    void infrastructureFailures() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        when(accessStates.get(OWNER, SCHEMA)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        MockHttpServletResponse down = run("/api/v1/mcp", "good", seen, tenant);
        assertThat(down.getStatus()).isEqualTo(500);
        assertThat(down.getContentAsString()).contains("INTERNAL_ERROR");

        org.mockito.Mockito.reset(accessStates);
        when(accessStates.get(OWNER, SCHEMA)).thenReturn(state("OWNER", true, "ACTIVE", null));
        org.mockito.Mockito.doThrow(new com.keevo.shared.domain.exception.DomainException("SERVICE_UNAVAILABLE"))
                .when(provisioner).ensureFoundation(SCHEMA);
        MockHttpServletResponse notReady = run("/api/v1/mcp", "good", seen, tenant);
        assertThat(notReady.getStatus()).isEqualTo(503);
        assertThat(notReady.getHeader("Retry-After")).isEqualTo("30");

        org.mockito.Mockito.reset(provisioner);
        after = null;
        when(accessStates.get(OWNER, SCHEMA)).thenThrow(new IllegalStateException("unexpected"));
        MockHttpServletResponse unexpected = run("/api/v1/mcp", "good", seen, tenant);
        assertThat(unexpected.getStatus()).isEqualTo(500);
        assertThat(unexpected.getContentAsString()).contains("INTERNAL_ERROR").doesNotContain("unexpected");
        assertThat(tenant.get()).as("chain never reached").isNull();
    }
}
