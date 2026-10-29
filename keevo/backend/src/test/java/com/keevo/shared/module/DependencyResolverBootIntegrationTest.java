package com.keevo.shared.module;

import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.module.application.EntitlementActivationEligibility;
import com.keevo.shared.module.infrastructure.EntitlementAspect;
import org.springframework.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.3 / 13.5 — the resolver beans wire in the real application context, on the shipped catalogue and the real
 * registry: a tenant with {@code pos} DISABLED (its requires {@code catalog}/{@code inventory} free, hence active)
 * gets the plan {@code [pos]}.
 */
class DependencyResolverBootIntegrationTest extends FullContextIntegrationTest {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final String tenant = randomSchema();
    private final UUID tenantId = UUID.randomUUID();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DependencyResolver resolver;

    @Autowired
    private ModuleActivationEligibility eligibility;

    @Autowired
    private ModuleRegistry registry;

    @Autowired
    private ApplicationContext context;

    /** A valid tenant schema ({@code kv_} + 6 random [a-z0-9]), unique per run. */
    private static String randomSchema() {
        SecureRandom random = new SecureRandom();
        StringBuilder schema = new StringBuilder("kv_");
        for (int i = 0; i < 6; i++) {
            schema.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return schema.toString();
    }

    @AfterEach
    void cleanUp() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM public.tenants WHERE id = ?", tenantId);
        registry.evict(tenant);
    }

    @Test
    void planActivationOfADisabledPosOnTheShippedCatalogue() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                        + "max_stores, plan_type, schema_name, status, name) "
                        + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')",
                tenantId, "KV-" + tenant.substring(3).toUpperCase(), tenant);
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) "
                + "VALUES (?, 'pos', 'DISABLED', 'FREE')", tenantId);

        // Story 13.5: exactly one eligibility bean (a second one would break the resolver's injection)
        assertThat(context.getBeansOfType(ModuleActivationEligibility.class)).hasSize(1);
        assertThat(eligibility).isInstanceOf(EntitlementActivationEligibility.class);
        assertThat(eligibility.check(tenant, registry.definition("pos").orElseThrow()).initialStatus())
                .isEqualTo(TenantModuleStatus.ACTIVE);
        // the entitlement layer is wired on the same registry, and its aspect is registered
        assertThat(context.getBean(EntitlementService.class).state(tenant, "pos")).isEqualTo(ModuleState.DISABLED);
        assertThat(context.getBeansOfType(EntitlementAspect.class)).hasSize(1);
        ActivationPlan plan = resolver.planActivation(tenant, "pos");
        assertThat(plan.moduleIds()).containsExactly("pos");
        assertThat(plan.alreadyActive()).containsExactly("catalog", "inventory");
        assertThat(plan.blocked()).isFalse();

        // pos is off: nothing active requires catalog through pos; inventory still requires catalog
        DeactivationCheck catalogCheck = resolver.checkDeactivation(tenant, "catalog");
        assertThat(catalogCheck.dependents()).doesNotContain("pos").contains("inventory");
        assertThat(resolver.checkDeactivation(tenant, "core").reasons())
                .containsExactly(new BlockReason(BlockReason.Code.CORE_MODULE, "core"));
    }
}
