package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.infrastructure.activity.UserActivityRecorder;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import com.keevo.shared.infrastructure.security.access.AccessState;
import com.keevo.shared.infrastructure.security.access.AccessStateService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthFilter — Story 16.11: the access token proves identity only; every right (role, store,
 * statuses, revocation cutoff, open session) comes from the server-side {@link AccessState}.
 * Real RS256 tokens (test keys), mocked access-state service.
 */
@DisplayName("JwtAuthFilter (identity token + server-side access state)")
class JwtAuthFilterTest {

    private static final String SCHEMA = "kv_abc123";

    private final JwtTokenProvider jwt = TestJwtKeys.provider(new JwtProperties(30, 365));
    private final AccessStateService accessStates = mock(AccessStateService.class);
    private final TenantSchemaSyncService sync = mock(TenantSchemaSyncService.class);
    private final UserActivityRecorder recorder = mock(UserActivityRecorder.class);
    private final FilterChain chain = mock(FilterChain.class);
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwt, new ObjectMapper(), sync, accessStates, recorder);
        // Default: the session check answers from the state (no database reload in unit tests).
        when(accessStates.withOpenSession(any(), any(), any(), any())).thenAnswer(inv -> {
            AccessState st = inv.getArgument(2);
            return st.isSessionOpen(inv.getArgument(3)) ? st : null;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static AccessState owner(Set<UUID> sessions) {
        return new AccessState("OWNER", true, "ACTIVE", null, null, sessions);
    }

    private static AccessState employee(UUID storeId, String status, boolean pwd) {
        return new AccessState("EMPLOYEE", true, "ACTIVE", null,
                new AccessState.EmployeeState(storeId, status, pwd, "Awa"), Set.of());
    }

    private static AccessState withTenantStatus(AccessState s, String status) {
        return new AccessState(s.role(), s.membershipActive(), status, s.tokensValidAfter(), s.employee(),
                s.openSessionIds());
    }

    private static MockHttpServletRequest request(String method, String path, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRequestURI(path);
        if (token != null) r.addHeader("Authorization", "Bearer " + token);
        return r;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    /** Captures the authentication seen by the chain. */
    private Authentication authSeenByChain(MockHttpServletRequest request) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return seen.get();
    }

    private static Set<String> authorities(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ── public paths, header and token shape ──────────────────────────────

    @Test
    void everyPublicPathOfSecurityConfigSkipsTheJwtFilter() {
        for (String path : SecurityConfig.PUBLIC_PATHS) {
            String concrete = path.replace("/**", "/x");
            var request = new MockHttpServletRequest("POST", concrete);
            request.setRequestURI(concrete);
            assertThat(filter.shouldNotFilter(request)).as(concrete).isTrue();
        }
        var sessions = new MockHttpServletRequest("GET", "/api/v1/auth/sessions");
        sessions.setRequestURI("/api/v1/auth/sessions");
        assertThat(filter.shouldNotFilter(sessions)).isFalse();
    }

    @Test
    @DisplayName("missing / non-Bearer header → 401 UNAUTHORIZED")
    void missingHeader() throws Exception {
        assertThat(run(request("GET", "/api/v1/products", null)).getContentAsString()).contains("UNAUTHORIZED");
        MockHttpServletRequest basic = new MockHttpServletRequest("GET", "/api/v1/products");
        basic.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = run(basic);
        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("expired token → 401 TOKEN_EXPIRED (the app renews silently)")
    void expiredToken() throws Exception {
        String expired = TestJwtKeys.token(UUID.randomUUID(), SCHEMA,
                Instant.now().minus(3, ChronoUnit.HOURS), Map.of());
        MockHttpServletResponse response = run(request("GET", "/api/v1/products", expired));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_EXPIRED");
        verify(accessStates, never()).get(any(), any());
    }

    @Test
    @DisplayName("malformed token / far-future iat → 401 UNAUTHORIZED")
    void malformedToken() throws Exception {
        assertThat(run(request("GET", "/api/v1/products", "not.a.jwt")).getContentAsString())
                .contains("UNAUTHORIZED");
        String future = TestJwtKeys.token(UUID.randomUUID(), SCHEMA,
                Instant.now().plus(10, ChronoUnit.MINUTES), Map.of());
        assertThat(run(request("GET", "/api/v1/products", future)).getContentAsString())
                .contains("UNAUTHORIZED");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("login_pending token → 401 TOKEN_INVALID, never reaches the MDC")
    void loginToken() throws Exception {
        MockHttpServletResponse response = run(request("GET", "/api/v1/products",
                jwt.generateLoginToken(UUID.randomUUID())));
        assertThat(response.getContentAsString()).contains("TOKEN_INVALID");
        assertThat(MDC.get("tenantId")).isNull();
        verify(accessStates, never()).get(any(), any());
    }

    @Test
    @DisplayName("invalid tenantId → 401 TOKEN_INVALID before any lookup")
    void invalidTenant() throws Exception {
        String token = jwt.generateAccessToken(UUID.randomUUID(), "kv_x'; DROP", UUID.randomUUID(), null);
        assertThat(run(request("GET", "/api/v1/products", token)).getContentAsString()).contains("TOKEN_INVALID");
        verify(accessStates, never()).get(any(), any());
    }

    // ── rights from the database, never from the token ────────────────────

    @Test
    @DisplayName("16.11 — role comes from the database: a token claiming OWNER for a DB EMPLOYEE gets ROLE_EMPLOYEE")
    void roleFromDatabaseNotToken() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        String token = TestJwtKeys.token(userId, SCHEMA, Instant.now(), Map.of("role", "OWNER"));
        when(accessStates.get(userId, SCHEMA)).thenReturn(employee(store, "ACTIVE", false));

        Authentication auth = authSeenByChain(request("POST", "/api/v1/sales", token));

        assertThat(authorities(auth)).containsExactly("ROLE_EMPLOYEE");
        assertThat(auth.getDetails()).isEqualTo(new AuthDetails("Awa", store, null));
    }

    @Test
    @DisplayName("16.11 — demoted manager: the next request carries the new role (403 is the controller's job)")
    void demotedRoleAppliesAtNextRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId, SCHEMA, sid, null);
        when(accessStates.get(userId, SCHEMA)).thenReturn(owner(Set.of(sid)));
        assertThat(authorities(authSeenByChain(request("GET", "/api/v1/products", token))))
                .containsExactly("ROLE_OWNER");

        when(accessStates.get(userId, SCHEMA)).thenReturn(new AccessState("EMPLOYEE", true, "ACTIVE", null,
                new AccessState.EmployeeState(UUID.randomUUID(), "ACTIVE", false, null), Set.of(sid)));
        assertThat(authorities(authSeenByChain(request("GET", "/api/v1/products", token))))
                .containsExactly("ROLE_EMPLOYEE");
    }

    @Test
    @DisplayName("valid owner token: TenantContext + MDC during the chain, sid in AuthDetails, all cleared after")
    void validOwner() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId, SCHEMA, sid, null);
        when(accessStates.get(userId, SCHEMA)).thenReturn(owner(Set.of(sid)));
        Map<String, String> mdc = new HashMap<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        doAnswer(inv -> {
            mdc.putAll(MDC.getCopyOfContextMap());
            tenant.set(TenantContext.getCurrentTenant());
            assertThat(CurrentActor.sessionIdOrNull()).isEqualTo(sid);
            return null;
        }).when(chain).doFilter(any(), any());

        MockHttpServletResponse response = run(request("GET", "/api/v1/products", token));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(tenant.get()).isEqualTo(SCHEMA);
        assertThat(mdc).containsEntry("tenantId", SCHEMA).containsEntry("userId", userId.toString());
        assertThat(TenantContext.getCurrentTenant()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        verify(sync).syncIfNeeded(SCHEMA);
        verify(recorder).record(userId, SCHEMA, "OWNER");
    }

    @Test
    @DisplayName("chain throws: TenantContext and MDC are still cleared, other MDC keys untouched")
    void chainThrows() throws Exception {
        UUID userId = UUID.randomUUID();
        when(accessStates.get(userId, SCHEMA)).thenReturn(owner(Set.of()));
        doThrow(new RuntimeException("downstream")).when(chain).doFilter(any(), any());
        MDC.put("traceId", "t-1");
        assertThatThrownBy(() -> run(request("GET", "/api/v1/products",
                jwt.generateAccessToken(userId, SCHEMA, null, null)))).isInstanceOf(RuntimeException.class);
        assertThat(TenantContext.getCurrentTenant()).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("t-1");
    }

    @Test
    @DisplayName("no membership / inactive membership / deleted account → 401 ACCOUNT_INACTIVE")
    void noMembership() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId, SCHEMA, null, null);
        when(accessStates.get(userId, SCHEMA)).thenReturn(null);
        assertThat(run(request("GET", "/api/v1/products", token)).getContentAsString()).contains("ACCOUNT_INACTIVE");

        when(accessStates.get(userId, SCHEMA)).thenReturn(new AccessState("OWNER", false, "ACTIVE", null, null, Set.of()));
        assertThat(run(request("GET", "/api/v1/products", token)).getContentAsString()).contains("ACCOUNT_INACTIVE");

        when(accessStates.get(userId, SCHEMA)).thenReturn(withTenantStatus(owner(Set.of()), "DELETED"));
        assertThat(run(request("GET", "/api/v1/products", token)).getContentAsString()).contains("ACCOUNT_INACTIVE");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("access-state lookup failure → 500 INTERNAL_ERROR (fail closed), nothing recorded")
    void lookupFailure() throws Exception {
        UUID userId = UUID.randomUUID();
        when(accessStates.get(userId, SCHEMA)).thenThrow(new DataAccessResourceFailureException("down"));
        MockHttpServletResponse response = run(request("GET", "/api/v1/products",
                jwt.generateAccessToken(userId, SCHEMA, null, null)));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("INTERNAL_ERROR");
        verify(chain, never()).doFilter(any(), any());
        verify(recorder, never()).record(any(), anyString(), anyString());
    }

    // ── account status ─────────────────────────────────────────────────────

    @Test
    @DisplayName("SUSPENDED (from the database) → writes 403 ACCOUNT_SUSPENDED, reads pass")
    void suspended() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestJwtKeys.token(userId, SCHEMA, Instant.now(), Map.of("tenantStatus", "ACTIVE"));
        when(accessStates.get(userId, SCHEMA)).thenReturn(withTenantStatus(owner(Set.of()), "SUSPENDED"));

        MockHttpServletResponse write = run(request("POST", "/api/v1/products", token));
        assertThat(write.getStatus()).isEqualTo(403);
        assertThat(write.getContentAsString()).contains("ACCOUNT_SUSPENDED");
        assertThat(run(request("GET", "/api/v1/products", token)).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("DELETION_PENDING → writes 403 ACCOUNT_DELETION_PENDING except the cancellation, reads pass")
    void deletionPending() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId, SCHEMA, null, null);
        when(accessStates.get(userId, SCHEMA)).thenReturn(withTenantStatus(owner(Set.of()), "DELETION_PENDING"));

        assertThat(run(request("POST", "/api/v1/products", token)).getContentAsString())
                .contains("ACCOUNT_DELETION_PENDING");
        assertThat(run(request("POST", "/api/v1/account/delete/cancel", token)).getStatus()).isEqualTo(200);
        assertThat(run(request("GET", "/api/v1/products", token)).getStatus()).isEqualTo(200);
    }

    // ── super-admin ────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPER_ADMIN (DB role) on the \"public\" system account is accepted")
    void superAdmin() throws Exception {
        UUID userId = UUID.randomUUID();
        when(accessStates.get(userId, "public")).thenReturn(new AccessState("SUPER_ADMIN", true, "ACTIVE",
                null, null, Set.of()));
        Authentication auth = authSeenByChain(request("GET", "/api/v1/admin/tenants",
                jwt.generateAccessToken(userId, "public", null, null)));
        assertThat(authorities(auth)).containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("a non-super-admin on \"public\" (even with a forged role claim) → 401 TOKEN_INVALID")
    void ownerOnPublic() throws Exception {
        UUID userId = UUID.randomUUID();
        when(accessStates.get(userId, "public")).thenReturn(new AccessState("OWNER", true, "ACTIVE", null, null, Set.of()));
        String token = TestJwtKeys.token(userId, "public", Instant.now(), Map.of("role", "SUPER_ADMIN"));
        assertThat(run(request("GET", "/api/v1/admin/tenants", token)).getContentAsString()).contains("TOKEN_INVALID");
    }

    // ── revocation cutoff and device sessions ──────────────────────────────

    @Test
    @DisplayName("iat before tokens_valid_after → 401 SESSION_REVOKED; same second (tva truncated) → accepted")
    void revocationCutoff() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant tva = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(400);
        when(accessStates.get(userId, SCHEMA)).thenReturn(new AccessState("OWNER", true, "ACTIVE", tva, null, Set.of()));

        String before = TestJwtKeys.token(userId, SCHEMA, tva.minusSeconds(5), Map.of());
        MockHttpServletResponse revoked = run(request("GET", "/api/v1/products", before));
        assertThat(revoked.getContentAsString()).contains("SESSION_REVOKED");
        assertThat(MDC.get("tenantId")).isNull();

        String sameSecond = TestJwtKeys.token(userId, SCHEMA, tva.truncatedTo(ChronoUnit.SECONDS), Map.of());
        assertThat(run(request("GET", "/api/v1/products", sameSecond)).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("16.11 — closed device session (sid no longer open) → 401 SESSION_REVOKED")
    void closedSession() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        AccessState state = owner(Set.of());
        when(accessStates.get(userId, SCHEMA)).thenReturn(state);
        org.mockito.Mockito.doReturn(null).when(accessStates).withOpenSession(eq(userId), eq(SCHEMA), eq(state), eq(sid));

        MockHttpServletResponse response = run(request("GET", "/api/v1/products",
                jwt.generateAccessToken(userId, SCHEMA, sid, null)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SESSION_REVOKED");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("16.11 — token without sid (issued before device sessions) is not checked against sessions")
    void legacyTokenWithoutSid() throws Exception {
        UUID userId = UUID.randomUUID();
        when(accessStates.get(userId, SCHEMA)).thenReturn(owner(Set.of()));
        assertThat(run(request("GET", "/api/v1/products", jwt.generateAccessToken(userId, SCHEMA, "OWNER")))
                .getStatus()).isEqualTo(200);
        verify(accessStates, never()).withOpenSession(any(), any(), any(), any());
    }

    @Test
    @DisplayName("16.11 — a token without sid older than the access-token lifetime (pre-deploy 60-day token) → 401 TOKEN_EXPIRED")
    void legacyTokenWithoutSidPastLifetime() throws Exception {
        UUID userId = UUID.randomUUID();
        String old = TestJwtKeys.token(userId, SCHEMA, Instant.now().minus(40, ChronoUnit.MINUTES),
                Map.of("role", "OWNER"));
        MockHttpServletResponse response = run(request("GET", "/api/v1/products", old));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TOKEN_EXPIRED");
        verify(accessStates, never()).get(any(), any());
    }

    @Test
    @DisplayName("16.11 — sid missing from the cached state: the filter goes on with the fresh state (new role)")
    void freshStateAfterSessionReload() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        AccessState stale = owner(Set.of());
        AccessState fresh = new AccessState("EMPLOYEE", true, "ACTIVE", null,
                new AccessState.EmployeeState(UUID.randomUUID(), "ACTIVE", false, "Awa"), Set.of(sid));
        when(accessStates.get(userId, SCHEMA)).thenReturn(stale);
        org.mockito.Mockito.doReturn(fresh).when(accessStates).withOpenSession(eq(userId), eq(SCHEMA), eq(stale), eq(sid));

        Authentication auth = authSeenByChain(request("GET", "/api/v1/products",
                jwt.generateAccessToken(userId, SCHEMA, sid, null)));

        assertThat(authorities(auth)).containsExactly("ROLE_EMPLOYEE");
    }

    @Test
    @DisplayName("16.11 — an owner with an employee row keeps its first name as actor label")
    void ownerFirstName() throws Exception {
        UUID userId = UUID.randomUUID();
        when(accessStates.get(userId, SCHEMA)).thenReturn(new AccessState("OWNER", true, "ACTIVE", null,
                new AccessState.EmployeeState(UUID.randomUUID(), "ACTIVE", false, "Loïc"), Set.of()));
        Authentication auth = authSeenByChain(request("GET", "/api/v1/products",
                jwt.generateAccessToken(userId, SCHEMA, null, null)));
        assertThat(auth.getDetails()).isEqualTo(new AuthDetails("Loïc", null, null));
    }

    // ── employee guards ────────────────────────────────────────────────────

    @Test
    @DisplayName("EMPLOYEE inactive / without row → 401 ACCOUNT_INACTIVE")
    void employeeInactive() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId, SCHEMA, null, null);
        when(accessStates.get(userId, SCHEMA)).thenReturn(employee(UUID.randomUUID(), "INACTIVE", false));
        assertThat(run(request("GET", "/api/v1/products", token)).getContentAsString()).contains("ACCOUNT_INACTIVE");
        when(accessStates.get(userId, SCHEMA)).thenReturn(new AccessState("EMPLOYEE", true, "ACTIVE", null, null, Set.of()));
        assertThat(run(request("GET", "/api/v1/products", token)).getContentAsString()).contains("ACCOUNT_INACTIVE");
    }

    @Test
    @DisplayName("16.11 — reassigned employee: a token without storeId follows the DB store; a legacy storeId claim that differs → STORE_REASSIGNED once")
    void storeReassigned() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();
        when(accessStates.get(userId, SCHEMA)).thenReturn(employee(storeB, "ACTIVE", false));

        Authentication auth = authSeenByChain(request("GET", "/api/v1/products",
                jwt.generateAccessToken(userId, SCHEMA, null, null)));
        assertThat(((AuthDetails) auth.getDetails()).storeId()).isEqualTo(storeB);

        String legacyA = TestJwtKeys.token(userId, SCHEMA, Instant.now(),
                Map.of("role", "EMPLOYEE", "storeId", UUID.randomUUID().toString()));
        assertThat(run(request("GET", "/api/v1/products", legacyA)).getContentAsString()).contains("STORE_REASSIGNED");

        String legacyB = TestJwtKeys.token(userId, SCHEMA, Instant.now(),
                Map.of("role", "EMPLOYEE", "storeId", storeB.toString()));
        assertThat(run(request("GET", "/api/v1/products", legacyB)).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("EMPLOYEE with password change pending (DB) → 403 PASSWORD_CHANGE_REQUIRED except change-password")
    void passwordChangeRequired() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId, SCHEMA, null, null);
        when(accessStates.get(userId, SCHEMA)).thenReturn(employee(UUID.randomUUID(), "ACTIVE", true));
        MockHttpServletResponse blocked = run(request("GET", "/api/v1/products", token));
        assertThat(blocked.getStatus()).isEqualTo(403);
        assertThat(blocked.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
        assertThat(run(request("POST", "/api/v1/auth/change-password", token)).getStatus()).isEqualTo(200);
        // The profile can be read (GET only) so the app learns the flag.
        assertThat(run(request("GET", "/api/v1/auth/profile", token)).getStatus()).isEqualTo(200);
        assertThat(run(request("POST", "/api/v1/auth/profile", token)).getStatus()).isEqualTo(403);
    }

    // ── activity ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("9-4 — a recorder failure never affects the request; rejected token records nothing")
    void activityRecorder() throws Exception {
        UUID userId = UUID.randomUUID();
        when(accessStates.get(userId, SCHEMA)).thenReturn(owner(Set.of()));
        doThrow(new RuntimeException("boom")).when(recorder).record(any(), anyString(), anyString());
        assertThat(run(request("GET", "/api/v1/products", jwt.generateAccessToken(userId, SCHEMA, null, null)))
                .getStatus()).isEqualTo(200);

        run(request("GET", "/api/v1/products", "bad.jwt"));
        verify(recorder, org.mockito.Mockito.times(1)).record(any(), anyString(), anyString());
    }
}
