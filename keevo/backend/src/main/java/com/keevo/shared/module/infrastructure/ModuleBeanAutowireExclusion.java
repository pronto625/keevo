package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ContributesExtension;
import com.keevo.shared.module.ProvidesCapability;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Makes every module-contributed bean of the core registries a non-autowire-candidate — {@link ProvidesCapability}
 * providers (Story 13.6) and {@link ContributesExtension} contributions (Story 13.7) — so no module can inject one
 * directly and skip the tenant check: {@code CapabilityRegistry.find} and {@code ExtensionRegistry.contributions}
 * (which look the beans up by annotation, unaffected) are the only way in. An injection attempt fails at startup
 * ({@code NoSuchBeanDefinitionException}; a collection injection gets none of them); the ArchUnit rule
 * {@code capability-injection} catches it at build time. Not covered: an explicit lookup through the bean factory
 * ({@code getBean}, {@code getBeansOfType}) still reaches these beans — modules must not do that.
 */
@Component
public class ModuleBeanAutowireExclusion implements BeanFactoryPostProcessor {

    static final List<Class<? extends Annotation>> REGISTRATION_ANNOTATIONS =
            List.of(ProvidesCapability.class, ContributesExtension.class);

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        for (Class<? extends Annotation> annotation : REGISTRATION_ANNOTATIONS) {
            for (String name : beanFactory.getBeanNamesForAnnotation(annotation)) {
                if (beanFactory.containsBeanDefinition(name)) {
                    beanFactory.getBeanDefinition(name).setAutowireCandidate(false);
                }
            }
        }
    }
}
