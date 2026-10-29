package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ProvidesCapability;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * Story 13.6 — makes every {@link ProvidesCapability} bean a non-autowire-candidate, so no module can inject a
 * capability implementation directly and skip the tenant check: {@code CapabilityRegistry.find} (which looks the
 * providers up by annotation, unaffected) is the only way in. An injection attempt fails at startup
 * ({@code NoSuchBeanDefinitionException}); the ArchUnit rule {@code capability-injection} catches it at build time.
 */
@Component
public class CapabilityProviderAutowireExclusion implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        for (String name : beanFactory.getBeanNamesForAnnotation(ProvidesCapability.class)) {
            if (beanFactory.containsBeanDefinition(name)) {
                beanFactory.getBeanDefinition(name).setAutowireCandidate(false);
            }
        }
    }
}
