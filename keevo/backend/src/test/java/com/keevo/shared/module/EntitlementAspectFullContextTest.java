package com.keevo.shared.module;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.infrastructure.EntitlementAspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.5 — {@link RequiresEntitlement} in the real application context (normal Spring Boot auto-proxying, no
 * extra {@code @EnableAspectJAutoProxy}), on a test-only bean gated on the shipped {@code crm} module: refused /
 * read-only / allowed, unknown and DELETED tenants, and the advice order (method security → entitlement →
 * transaction: a refused call never begins a transaction).
 */
@Import(EntitlementAspectFullContextTest.GatedCrmService.class)
class EntitlementAspectFullContextTest extends FullContextIntegrationTest {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** Test-only bean: each method reports whether it runs inside a transaction. */
    static class GatedCrmService {

        @RequiresEntitlement("crm")
        @Transactional
        public boolean write() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        @Transactional(readOnly = true)
        public boolean read() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @RequiresEntitlement("crm")
        @PreAuthorize("hasRole('OWNER')")
        @Transactional
        public boolean securedWrite() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    @Autowired private GatedCrmService service;
    @Autowired private DataSource dataSource;
    @Autowired private ModuleRegistry registry;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String tenant = randomSchema();
    private final UUID tenantId = UUID.randomUUID();
    private final AtomicInteger transactionsBegun = new AtomicInteger();
    private final TransactionExecutionListener counter = new TransactionExecutionListener() {
        @Override
        public void beforeBegin(TransactionExecution transaction) {
            transactionsBegun.incrementAndGet();
        }
    };

    private static String randomSchema() {
        SecureRandom random = new SecureRandom();
        StringBuilder schema = new StringBuilder("kv_");
        for (int i = 0; i < 6; i++) {
            schema.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return schema.toString();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private void tenantRow(String status) {
        jdbc().update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, ?, 'Test')",
                tenantId, "KV-" + tenant.substring(3).toUpperCase(), tenant, status);
    }

    @BeforeEach
    void bindTenant() {
        TenantContext.setCurrentTenant(tenant);
        ((AbstractPlatformTransactionManager) transactionManager).addListener(counter);
    }

    @AfterEach
    void cleanUp() {
        ((AbstractPlatformTransactionManager) transactionManager).getTransactionExecutionListeners().remove(counter);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        jdbc().update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        jdbc().update("DELETE FROM public.tenants WHERE id = ?", tenantId);
        registry.evict(tenant);
    }

    private static String code(Runnable call) {
        try {
            call.run();
            return null;
        } catch (DomainException e) {
            return e.getDomainCode();
        }
    }

    @Test
    @DisplayName("free crm without a row → passes, inside its transaction")
    void entitledPasses() {
        tenantRow("ACTIVE");
        assertThat(service.write()).isTrue();
        assertThat(service.read()).isTrue();
        assertThat(transactionsBegun).hasValue(2);
    }

    @Test
    @DisplayName("crm DISABLED → MODULE_NOT_ENTITLED before any transaction begins")
    void notEntitledRefusedOutsideTransaction() {
        tenantRow("ACTIVE");
        jdbc().update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) "
                + "VALUES (?, 'crm', 'DISABLED', 'FREE')", tenantId);
        assertThat(code(service::write)).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(code(service::read)).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(transactionsBegun).hasValue(0);
    }

    @Test
    @DisplayName("crm ACTIVE past expiry, in grace → write MODULE_READ_ONLY (no transaction), read passes")
    void readOnly() {
        tenantRow("ACTIVE");
        jdbc().update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier, expires_at, grace_until) "
                + "VALUES (?, 'crm', 'ACTIVE', 'STANDARD', now() - interval '1 hour', now() + interval '7 days')",
                tenantId);
        assertThat(code(service::write)).isEqualTo("MODULE_READ_ONLY");
        assertThat(transactionsBegun).hasValue(0);
        assertThat(service.read()).isTrue();
    }

    @Test
    @DisplayName("unknown tenant and DELETED tenant → only core: MODULE_NOT_ENTITLED")
    void unknownAndDeletedTenants() {
        assertThat(code(service::write)).isEqualTo("MODULE_NOT_ENTITLED"); // no tenants row at all
        tenantRow("DELETED");
        assertThat(code(service::read)).isEqualTo("MODULE_NOT_ENTITLED");
    }

    @Test
    @DisplayName("no tenant bound → IllegalStateException, no transaction")
    void noTenant() {
        TenantContext.clear();
        assertThatThrownBy(service::write).isInstanceOf(IllegalStateException.class);
        assertThat(transactionsBegun).hasValue(0);
    }

    @Test
    @DisplayName("advice order on the real proxy: method security → entitlement → transaction")
    void adviceOrder() {
        List<Advisor> advisors = Arrays.asList(((Advised) service).getAdvisors());
        // Spring Security wraps its interceptors (AdvisorWrapper / DeferringMethodInterceptor): match by package
        int security = indexOf(advisors, a -> a instanceof AuthorizationManagerBeforeMethodInterceptor
                || a.getClass().getName().startsWith("org.springframework.security."));
        int entitlement = indexOf(advisors, a -> a.getAdvice() instanceof AbstractAspectJAdvice advice
                && advice.getAspectJAdviceMethod().getDeclaringClass() == EntitlementAspect.class);
        int transaction = indexOf(advisors, a -> a.getAdvice() instanceof TransactionInterceptor);
        assertThat(security).as("method security advisor").isNotNegative();
        assertThat(entitlement).as("entitlement advisor").isGreaterThan(security);
        assertThat(transaction).as("transaction advisor").isGreaterThan(entitlement);

        // behaviour: an unauthenticated call on a not-entitled module is refused by method security first
        assertThatThrownBy(service::securedWrite).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        // authenticated without the role: still refused by method security, not by the entitlement
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
        assertThatThrownBy(service::securedWrite).isInstanceOf(AuthorizationDeniedException.class);
        // authenticated owner, not entitled: the entitlement refuses, before the transaction
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        assertThat(code(service::securedWrite)).isEqualTo("MODULE_NOT_ENTITLED");
        assertThat(transactionsBegun).hasValue(0);
    }

    private static int indexOf(List<Advisor> advisors, java.util.function.Predicate<Advisor> match) {
        for (int i = 0; i < advisors.size(); i++) {
            if (match.test(advisors.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
