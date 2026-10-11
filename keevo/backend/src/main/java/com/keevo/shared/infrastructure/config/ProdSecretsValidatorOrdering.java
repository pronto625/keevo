package com.keevo.shared.infrastructure.config;

import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * ProdSecretsValidatorOrdering — Forces {@link ProdSecretsValidator} to run before any
 * {@link DataSource} is created (prod/staging only).
 *
 * <p>Without it, component-scanned beans that need JPA (e.g. in {@code com.keevo.identity})
 * can be instantiated before {@code com.keevo.shared}, so Hikari/Flyway would connect — or fail
 * on DB auth — before the validator runs, and the explicit {@code [FailFast]} message would be lost.
 *
 * <p>Story v1s-12-13.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"prod", "staging"})
public class ProdSecretsValidatorOrdering {

    /** Bean name of the component-scanned {@link ProdSecretsValidator}. */
    static final String VALIDATOR_BEAN_NAME = "prodSecretsValidator";

    @Bean
    static DataSourceDependsOnProdSecretsValidator dataSourceDependsOnProdSecretsValidator() {
        return new DataSourceDependsOnProdSecretsValidator();
    }

    /** Adds {@code dependsOn("prodSecretsValidator")} to every {@link DataSource} bean definition. */
    static final class DataSourceDependsOnProdSecretsValidator
            extends AbstractDependsOnBeanFactoryPostProcessor {

        DataSourceDependsOnProdSecretsValidator() {
            super(DataSource.class, VALIDATOR_BEAN_NAME);
        }
    }
}
