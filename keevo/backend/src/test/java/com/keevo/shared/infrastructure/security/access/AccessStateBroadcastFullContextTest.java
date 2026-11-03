package com.keevo.shared.infrastructure.security.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.activity.UserActivityRecorder;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.AuthDetails;
import com.keevo.shared.infrastructure.security.JwtAuthFilter;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import com.keevo.shared.infrastructure.security.TestAccess;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Story 16.11 — AC: a right removed in the database is refused at the next request on <b>every</b>
 * instance. Two independent filter instances (each with its own access-state cache and its own
 * {@code LISTEN keevo_access_state} connection) share the database, like two servers behind the
 * load balancer. Both caches are primed first; the change is then made on the database; both
 * instances must apply it well before the 30 s TTL (NOTIFY eviction).
 */
@DisplayName("16.11 — access state evicted on every instance (LISTEN/NOTIFY)")
class AccessStateBroadcastFullContextTest extends FullContextIntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwt;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired ModuleProvisioner moduleProvisioner;
    @Autowired AccessStateLoader loader;
    @Autowired EmployeeRepository employeeRepository;

    private final List<AccessStateNotificationListener> listeners = new ArrayList<>();
    private JwtAuthFilter instanceA;
    private JwtAuthFilter instanceB;
    private AccessStateChangePublisher publisherA;
    private String schema;
    private UUID storeId;

    @BeforeEach
    void setUp() throws Exception {
        AccessStateService cacheA = new AccessStateService(loader, Clock.systemUTC());
        AccessStateService cacheB = new AccessStateService(loader, Clock.systemUTC());
        instanceA = filter(cacheA);
        instanceB = filter(cacheB);
        publisherA = new AccessStateChangePublisher(jdbc, cacheA);
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        storeId = jdbc.queryForObject("SELECT id FROM \"" + schema + "\".stores LIMIT 1", UUID.class);
        for (AccessStateNotificationListener l : listeners) {
            assertThat(l.awaitListening(Duration.ofSeconds(10))).isTrue();
        }
    }

    @AfterEach
    void tearDown() {
        listeners.forEach(AccessStateNotificationListener::stop);
        SecurityContextHolder.clearContext();
    }

    private JwtAuthFilter filter(AccessStateService cache) {
        AccessStateNotificationListener listener = new AccessStateNotificationListener(dataSource, cache, true);
        listener.start();
        listeners.add(listener);
        return new JwtAuthFilter(jwt, new ObjectMapper(), moduleProvisioner, cache, mock(UserActivityRecorder.class));
    }

    /** Status code and authorities seen by the chain for one request on one instance. */
    private record Outcome(int status, String body, Set<String> authorities) {
    }

    private Outcome call(JwtAuthFilter instance, String token, String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/products");
        request.setRequestURI("/api/v1/products");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Set<String>> seen = new AtomicReference<>(Set.of());
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).collect(Collectors.toSet()));
            return null;
        }).when(chain).doFilter(any(), any());
        instance.doFilter(request, response, chain);
        return new Outcome(response.getStatus(), response.getContentAsString(), seen.get());
    }

    /** Polls (≤ 5 s, far below the 30 s TTL) until {@code condition} holds. */
    private static void eventually(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.get()) {
            assertThat(System.nanoTime()).as("not applied within 5 s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private Outcome unchecked(JwtAuthFilter instance, String token) {
        try {
            return call(instance, token, "POST");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("demoted manager (role changed in the database by any code path) → both instances see EMPLOYEE")
    void demotionReachesBothInstances() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Paul', 'Ekane', false)", userId, storeId);
        String token = TestAccess.token(jdbc, jwt, userId, schema, "OWNER");
        assertThat(call(instanceA, token, "POST").authorities()).containsExactly("ROLE_OWNER");
        assertThat(call(instanceB, token, "POST").authorities()).containsExactly("ROLE_OWNER");

        // A plain SQL update — no service, no explicit eviction: the membership trigger broadcasts it.
        jdbc.update("UPDATE public.user_tenant_memberships SET role = 'EMPLOYEE' WHERE user_id = ?", userId);

        eventually(() -> unchecked(instanceA, token).authorities().equals(Set.of("ROLE_EMPLOYEE"))
                && unchecked(instanceB, token).authorities().equals(Set.of("ROLE_EMPLOYEE")));
    }

    @Test
    @DisplayName("deactivated employee (employee row + publisher) → refused on both instances; no eviction → still cached")
    void deactivationReachesBothInstances() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, 'Awa', 'Ngo', false)", userId, storeId);
        String token = TestAccess.token(jdbc, jwt, userId, schema, "EMPLOYEE");
        assertThat(call(instanceA, token, "POST").status()).isEqualTo(200);
        assertThat(call(instanceB, token, "POST").status()).isEqualTo(200);

        // Without a broadcast the 30 s cache still answers (proves the eviction below is the NOTIFY).
        jdbc.update("UPDATE \"" + schema + "\".employees SET status = 'INACTIVE' WHERE user_id = ?", userId);
        assertThat(call(instanceB, token, "POST").status()).isEqualTo(200);

        // Instance A's service announces the change (as EmployeeRepositoryAdapter does), in a transaction.
        tx.executeWithoutResult(s -> publisherA.userChanged(userId));

        eventually(() -> unchecked(instanceA, token).body().contains("ACCOUNT_INACTIVE")
                && unchecked(instanceB, token).body().contains("ACCOUNT_INACTIVE"));
    }

    @Test
    @DisplayName("reassigned employee through the Spring-wired repository (transaction) → both instances see the new store")
    void reassignmentThroughRepositoryReachesBothInstances() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".employees (id, user_id, store_id, first_name, last_name, "
                + "password_change_required) VALUES (?, ?, ?, 'Awa', 'Ngo', false)", employeeId, userId, storeId);
        UUID storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".stores (id, name, type, is_active, created_at, updated_at) "
                + "VALUES (?, 'Boutique B', 'STORE', true, now(), now())", storeB);
        String token = TestAccess.token(jdbc, jwt, userId, schema, "EMPLOYEE");
        assertThat(storeSeen(instanceA, token)).isEqualTo(storeId);
        assertThat(storeSeen(instanceB, token)).isEqualTo(storeId);

        TenantContext.setCurrentTenant(schema);
        try {
            tx.executeWithoutResult(s -> employeeRepository.updateStoreId(employeeId, storeB));
        } finally {
            TenantContext.clear();
        }

        eventually(() -> storeB.equals(uncheckedStore(instanceA, token))
                && storeB.equals(uncheckedStore(instanceB, token)));
    }

    private UUID storeSeen(JwtAuthFilter instance, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setRequestURI("/api/v1/products");
        request.addHeader("Authorization", "Bearer " + token);
        AtomicReference<UUID> seen = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seen.set(((AuthDetails) SecurityContextHolder.getContext().getAuthentication().getDetails()).storeId());
            return null;
        }).when(chain).doFilter(any(), any());
        instance.doFilter(request, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    private UUID uncheckedStore(JwtAuthFilter instance, String token) {
        try {
            return storeSeen(instance, token);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("account suspended / deletion pending (tenants trigger) → next write refused on both instances")
    void suspensionReachesBothInstances() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestAccess.token(jdbc, jwt, userId, schema, "OWNER");
        assertThat(call(instanceA, token, "POST").status()).isEqualTo(200);
        assertThat(call(instanceB, token, "POST").status()).isEqualTo(200);

        jdbc.update("UPDATE public.tenants SET status = 'SUSPENDED' WHERE schema_name = ?", schema);
        eventually(() -> unchecked(instanceA, token).body().contains("ACCOUNT_SUSPENDED")
                && unchecked(instanceB, token).body().contains("ACCOUNT_SUSPENDED"));

        jdbc.update("UPDATE public.tenants SET status = 'DELETION_PENDING' WHERE schema_name = ?", schema);
        eventually(() -> unchecked(instanceA, token).body().contains("ACCOUNT_DELETION_PENDING")
                && unchecked(instanceB, token).body().contains("ACCOUNT_DELETION_PENDING"));
    }

    @Test
    @DisplayName("a rolled-back change evicts nothing on the other instance (NOTIFY is delivered on commit)")
    void rolledBackChangeIsNotBroadcast() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestAccess.token(jdbc, jwt, userId, schema, "OWNER");
        assertThat(call(instanceB, token, "POST").authorities()).containsExactly("ROLE_OWNER");

        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE public.user_tenant_memberships SET role = 'EMPLOYEE' WHERE user_id = ?", userId);
            s.setRollbackOnly();
        });
        Thread.sleep(300);
        assertThat(call(instanceB, token, "POST").authorities()).containsExactly("ROLE_OWNER");
    }
}
