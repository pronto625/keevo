package com.keevo.shared.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.event.DeliverOnce;
import com.keevo.shared.module.ModuleRegistry;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Story 12.4 — wires {@link ModuleListenerInterceptor} around every {@code @ApplicationModuleListener}
 * method (tenant binding from the event) and every {@code @DeliverOnce} method (receipts).
 *
 * <p>Infrastructure role: the advisor is picked up by whichever auto-proxy creator is active, like
 * Spring Modulith's own completion-registering advisor. Its collaborators are resolved lazily so the
 * advisor bean does not force early initialisation of the DataSource / ObjectMapper.
 */
@Configuration(proxyBeanMethods = false)
public class ModuleListenerConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor moduleListenerTenantAdvisor(ObjectProvider<EventListenerReceipts> receipts,
                                               ObjectProvider<ObjectMapper> objectMapper) {
        ModuleListenerInterceptor interceptor = new ModuleListenerInterceptor(receipts::getObject, objectMapper::getObject);
        Pointcut pointcut = new ComposablePointcut(
                new AnnotationMatchingPointcut(null, ApplicationModuleListener.class, true))
                .union(new AnnotationMatchingPointcut(null, DeliverOnce.class, true));
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, interceptor);
        advisor.setOrder(ModuleListenerInterceptor.ORDER);
        return advisor;
    }

    /**
     * Story 13.9 — the per-tenant {@code @ModuleScoped} gate around event listeners, async and synchronous (see
     * {@link ModuleScopedListenerInterceptor}). The registry is resolved on first use.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor moduleScopedListenerAdvisor(ObjectProvider<ModuleRegistry> registry) {
        ModuleScopedListenerInterceptor interceptor = new ModuleScopedListenerInterceptor(registry::getObject);
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(new ModuleScopedListenerInterceptor.Pointcut(),
                interceptor);
        advisor.setOrder(ModuleScopedListenerInterceptor.ORDER);
        return advisor;
    }
}
