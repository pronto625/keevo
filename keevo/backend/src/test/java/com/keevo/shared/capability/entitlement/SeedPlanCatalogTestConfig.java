package com.keevo.shared.capability.entitlement;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test slices (Story 19.7a): the plan catalogue of the V42 seed, without a database. */
@TestConfiguration
public class SeedPlanCatalogTestConfig {

    @Bean
    PlanCatalog seedPlanCatalog() {
        return PlanCatalog.SEED;
    }
}
