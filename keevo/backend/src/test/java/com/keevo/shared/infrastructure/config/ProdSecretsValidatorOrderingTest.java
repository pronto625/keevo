package com.keevo.shared.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1s-12-13 follow-up — pins the ordering guarantee directly at the bean-definition level
 * (the context tests only prove it indirectly) and the profiles it applies to.
 */
@DisplayName("ProdSecretsValidatorOrdering")
class ProdSecretsValidatorOrderingTest {

    @Test
    @DisplayName("every DataSource bean definition depends on prodSecretsValidator")
    void dataSourceDependsOnValidator() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("dataSource", new RootBeanDefinition(HikariDataSource.class));
        factory.registerBeanDefinition("otherBean", new RootBeanDefinition(String.class));

        ProdSecretsValidatorOrdering.dataSourceDependsOnProdSecretsValidator()
                .postProcessBeanFactory(factory);

        assertThat(factory.getBeanDefinition("dataSource").getDependsOn())
                .containsExactly(ProdSecretsValidatorOrdering.VALIDATOR_BEAN_NAME);
        assertThat(factory.getBeanDefinition("otherBean").getDependsOn()).isNullOrEmpty();
    }

    @Test
    @DisplayName("active under prod AND staging, like ProdSecretsValidator")
    void activeUnderSameProfilesAsValidator() {
        Profile ordering = ProdSecretsValidatorOrdering.class.getAnnotation(Profile.class);
        Profile validator = ProdSecretsValidator.class.getAnnotation(Profile.class);
        assertThat(ordering.value()).containsExactlyInAnyOrder("prod", "staging");
        assertThat(ordering.value()).containsExactlyInAnyOrder(validator.value());
    }

    @Test
    @DisplayName("bean name constant matches the component-scanned validator's default name")
    void beanNameMatchesValidator() {
        String simple = ProdSecretsValidator.class.getSimpleName();
        String defaultName = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        assertThat(ProdSecretsValidatorOrdering.VALIDATOR_BEAN_NAME).isEqualTo(defaultName);
    }
}
