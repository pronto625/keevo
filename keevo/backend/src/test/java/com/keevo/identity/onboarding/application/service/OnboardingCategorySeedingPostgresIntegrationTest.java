package com.keevo.identity.onboarding.application.service;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.onboarding.application.strategy.ClothingTemplateStrategy;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingCommand;
import com.keevo.identity.onboarding.domain.port.in.CompleteOnboardingUseCase;
import com.keevo.identity.onboarding.domain.port.in.OnboardingResult;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 12.6a — onboarding seeds the sector template's root categories through {@code TenantOnboardedEvent}
 * (identity → catalogue event → catalog's synchronous BEFORE_COMMIT seeder), on PostgreSQL with the full
 * application context: committed onboarding → template categories persisted (active, non-custom, root);
 * rolled-back onboarding → none.
 */
class OnboardingCategorySeedingPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired TenantFactory tenantFactory;
    @Autowired CompleteOnboardingUseCase onboarding;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private String newTenant() {
        return tx.execute(status -> tenantFactory.create()).getSchemaName();
    }

    private List<Map<String, Object>> rootCategories(String schema) {
        return jdbc.queryForList("SELECT name, is_active, is_custom FROM " + schema
                + ".categories WHERE parent_id IS NULL ORDER BY name");
    }

    @Test
    @DisplayName("committed onboarding → the template's root categories are persisted, active and non-custom")
    void committedOnboarding_seedsTemplateCategories() {
        String schema = newTenant();
        List<String> template = new ClothingTemplateStrategy().getDefaultCategories();
        int before = rootCategories(schema).size();

        TenantContext.setCurrentTenant(schema);
        OnboardingResult result = onboarding.complete(
                new CompleteOnboardingCommand(SectorType.CLOTHING, "Boutique IT", UUID.randomUUID()));
        TenantContext.clear();

        List<Map<String, Object>> roots = rootCategories(schema);
        assertThat(roots).hasSize(before + template.size());
        assertThat(roots).extracting(r -> (String) r.get("name")).containsAll(template);
        assertThat(roots).filteredOn(r -> template.contains((String) r.get("name"))).allSatisfy(r -> {
            assertThat(r.get("is_active")).isEqualTo(true);
            assertThat(r.get("is_custom")).isEqualTo(false);
        });
        assertThat(result.categoriesCreated()).isEqualTo(template.size());
    }

    @Test
    @DisplayName("rolled-back onboarding → no category persisted")
    void rolledBackOnboarding_seedsNothing() {
        String schema = newTenant();
        int before = rootCategories(schema).size();

        TenantContext.setCurrentTenant(schema);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            onboarding.complete(new CompleteOnboardingCommand(SectorType.CLOTHING, "Boutique IT", UUID.randomUUID()));
            throw new IllegalStateException("business error after onboarding (test)");
        })).isInstanceOf(IllegalStateException.class);
        TenantContext.clear();

        assertThat(rootCategories(schema)).hasSize(before);
    }
}
