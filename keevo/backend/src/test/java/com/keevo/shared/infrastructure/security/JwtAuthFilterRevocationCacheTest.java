package com.keevo.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.adapter.out.persistence.impl.CachedTokenRevocationAdapter;
import com.keevo.identity.auth.adapter.out.persistence.impl.JdbcTokenRevocationAdapter;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * v1s-12-12 follow-up — revocation round-trip at the filter surface, with the REAL caching
 * decorator ({@link CachedTokenRevocationAdapter}) over the real JDBC adapter (only
 * {@link JdbcTemplate} is mocked, backed by a "committed" cutoff).
 *
 * <p>Scenario of the spec AC: a request reading the cutoff while the revoking transaction is still
 * open re-populates the cache with the pre-revocation value; the post-commit invalidation must
 * evict it so the next request after commit is rejected with 401 {@code SESSION_REVOKED}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter + CachedTokenRevocationAdapter — revoke round-trip")
class JwtAuthFilterRevocationCacheTest {

    private static final String TENANT = "kv_abc123";
    private static final String TOKEN = "owner.jwt.token";

    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock FilterChain filterChain;
    @Mock TenantSchemaSyncService tenantSchemaSyncService;
    @Mock JdbcTemplate filterJdbc;
    @Mock JdbcTemplate revocationJdbc;

    private final AtomicReference<Instant> committedCutoff = new AtomicReference<>();
    private CachedTokenRevocationAdapter revocation;
    private JwtAuthFilter filter;
    private UUID userId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userId = UUID.randomUUID();
        revocation = new CachedTokenRevocationAdapter(
                new JdbcTokenRevocationAdapter(revocationJdbc), Clock.systemUTC());
        filter = new JwtAuthFilter(jwtTokenProvider, new ObjectMapper(),
                tenantSchemaSyncService, filterJdbc, revocation);

        when(revocationJdbc.queryForObject(anyString(), any(RowMapper.class), eq(userId), eq(TENANT)))
                .thenAnswer(inv -> committedCutoff.get());

        Claims claims = Jwts.claims().subject(userId.toString()).build();
        when(jwtTokenProvider.parseToken(TOKEN)).thenReturn(claims);
        when(jwtTokenProvider.extractScope(claims)).thenReturn("access");
        when(jwtTokenProvider.extractTenantId(claims)).thenReturn(TENANT);
        when(jwtTokenProvider.extractRole(claims)).thenReturn("OWNER");
        when(jwtTokenProvider.extractUserId(claims)).thenReturn(userId);
        when(jwtTokenProvider.extractTenantStatus(claims)).thenReturn("ACTIVE");
        // Token issued 10 min ago — older than any cutoff set by the revoke below.
        lenient().when(jwtTokenProvider.extractIssuedAt(claims))
                .thenReturn(Instant.now().minus(10, ChronoUnit.MINUTES));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse call() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, filterChain);
        SecurityContextHolder.clearContext();
        return response;
    }

    @Test
    @DisplayName("request during the open revoke tx re-caches the old cutoff; after commit → 401 SESSION_REVOKED")
    void staleRepopulationIsEvictedAfterCommit() throws Exception {
        // 1. Warm the cache: never revoked → request passes.
        assertThat(call().getStatus()).isEqualTo(200);

        // 2. Revoking transaction opens; the revoke UPDATE is not committed yet.
        TransactionSynchronizationManager.initSynchronization();
        revocation.revokeAllSessions(userId, TENANT);
        verify(revocationJdbc).update(anyString(), eq(userId), eq(TENANT));

        // 3. A concurrent request still reads the committed (old) state → passes and re-caches it.
        assertThat(call().getStatus()).isEqualTo(200);

        // 4. Commit: the cutoff becomes visible, then afterCommit callbacks run.
        committedCutoff.set(Instant.now());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();

        // 5. Next request with the old token is rejected — no stale cache entry survives.
        MockHttpServletResponse after = call();
        assertThat(after.getStatus()).isEqualTo(401);
        assertThat(after.getContentAsString()).contains("SESSION_REVOKED");
        verify(filterChain, times(2)).doFilter(any(), any());
    }
}
