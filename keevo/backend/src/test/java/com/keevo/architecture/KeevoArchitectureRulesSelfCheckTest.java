package com.keevo.architecture;

import com.keevo.architecture.KeevoArchitectureRules.Rule;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the Keevo rules themselves on the fixtures of {@code ..architecture.fixture..} (Story 12.6b). */
class KeevoArchitectureRulesSelfCheckTest {

    private static final String PACKAGE = "com.keevo.architecture.fixture.application.service.";
    private static final String FIXTURES = PACKAGE + "ArchitectureRuleFixtures$";

    private static JavaClasses fixtures;

    @BeforeAll
    static void importFixtures() {
        fixtures = new ClassFileImporter().importPackages("com.keevo.architecture.fixture");
    }

    @Test
    void tenantSchemaRuleFlagsOnlyTheUnvalidatedQuery() {
        assertThat(KeevoArchitectureRules.violations(Rule.TENANT_SCHEMA_JDBC, fixtures))
            .containsOnlyKeys(PACKAGE + "UnvalidatedSchemaQuery");
    }

    @Test
    void webRuleFlagsServletTypeInApplicationService() {
        assertThat(KeevoArchitectureRules.violations(Rule.WEB_FREE_APPLICATION, fixtures).values())
            .anySatisfy(detail -> assertThat(detail).contains("jakarta.servlet.http.HttpServletRequest"));
    }

    @Test
    void transactionalPublisherRuleFlagsOnlyTheNonTransactionalPublisher() {
        assertThat(KeevoArchitectureRules.violations(Rule.TRANSACTIONAL_PUBLISHERS, fixtures))
            .containsOnlyKeys(FIXTURES + "NonTransactionalPublisher#publish()", FIXTURES + "OverloadedPublisher#publish(int)");
    }

    @Test
    void capabilityInjectionRuleFlagsFieldAndConstructorButNotTheRegistryUser() {
        assertThat(KeevoArchitectureRules.violations(Rule.CAPABILITY_INJECTION, fixtures))
            .containsOnlyKeys(FIXTURES + "CapabilityFieldConsumer", FIXTURES + "CapabilityConstructorConsumer");
    }

    @Test
    void persistenceRuleFlagsEveryPersistenceTypeOutsideTheAdapter() {
        // fixtures outside com.keevo: never seen by Boot/JPA scanning
        JavaClasses outside = new ClassFileImporter().importPackages("archfixture");
        assertThat(KeevoArchitectureRules.violations(Rule.PERSISTENCE_LOCATION, outside)).containsOnlyKeys(
            "archfixture.domain.FixtureEntity", "archfixture.domain.FixtureEmbeddable",
            "archfixture.domain.FixtureMappedSuperclass", "archfixture.domain.FixtureRepositoryAdapter",
            "archfixture.domain.FixtureSpringDataRepository");
    }
}
