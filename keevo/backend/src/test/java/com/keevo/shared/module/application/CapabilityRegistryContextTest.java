package com.keevo.shared.module.application;

import com.keevo.shared.module.Capability;
import com.keevo.shared.module.CapabilityRegistry;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ProvidesCapability;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.infrastructure.ModuleBeanAutowireExclusion;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.core.NestedExceptionUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 13.6 — {@link CapabilityRegistryService} in a real Spring container: provider discovery by bean name (JDK
 * proxy, {@code @Bean} method, sub-capability), singleton-only providers, providers not injectable, lazy
 * initialization and a {@code find} from a consumer's init hook.
 */
class CapabilityRegistryContextTest {

    private static final String TENANT = "kv_ctx001";

    public interface GreetingPort extends Capability {
        String greet();
    }

    public interface FancyGreetingPort extends GreetingPort {
    }

    public interface FarewellPort extends Capability {
        String bye();
    }

    @ProvidesCapability(module = "crm")
    public static class CrmGreeting implements GreetingPort {
        @Override
        public String greet() {
            return "crm";
        }
    }

    @ProvidesCapability(module = "crm")
    public static class CrmFancyGreeting implements FancyGreetingPort {
        @Override
        public String greet() {
            return "fancy";
        }
    }

    @ProvidesCapability(module = "crm")
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public static class PrototypeGreeting extends CrmGreeting {
    }

    @ProvidesCapability(module = "crm")
    @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE, proxyMode = ScopedProxyMode.TARGET_CLASS)
    public static class ScopedProxyGreeting extends CrmGreeting {
    }

    @Configuration(proxyBeanMethods = false)
    public static class BeanMethodProvider {
        @Bean
        @ProvidesCapability(module = "pos")
        FarewellPort farewell() {
            return () -> "bye";
        }
    }

    /** Wraps {@link CrmGreeting} in a JDK (interface) proxy. */
    public static class JdkProxying implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof CrmGreeting) {
                ProxyFactory factory = new ProxyFactory(bean);
                factory.setInterfaces(GreetingPort.class);
                factory.setProxyTargetClass(false);
                return factory.getProxy();
            }
            return bean;
        }
    }

    public static class InjectingConsumer {
        public InjectingConsumer(GreetingPort port) {
        }
    }

    public static class InitHookConsumer {
        static volatile Optional<GreetingPort> found;

        private final CapabilityRegistry capabilities;

        public InitHookConsumer(CapabilityRegistry capabilities) {
            this.capabilities = capabilities;
        }

        @PostConstruct
        void init() {
            found = capabilities.find(GreetingPort.class, TENANT);
        }
    }

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private AnnotationConfigApplicationContext context(List<String> crmProvides, List<String> posProvides,
                                                       boolean lazy, Class<?>... components) {
        ModuleRegistry registry = mock(ModuleRegistry.class);
        when(registry.moduleState(eq(TENANT), anyString())).thenReturn(ModuleState.ENABLED);
        ModuleCatalog catalog = new ModuleCatalog(List.of(
                TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withCapabilities("crm", crmProvides, List.of()),
                TestModuleCatalogs.withCapabilities("pos", posProvides, List.of())));
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        if (lazy) {
            ctx.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
        }
        ctx.registerBean(ModuleRegistry.class, () -> registry);
        ctx.registerBean(ModuleCatalog.class, () -> catalog);
        ctx.register(CapabilityRegistryService.class, ModuleBeanAutowireExclusion.class);
        if (components.length > 0) {
            ctx.register(components);
        }
        return ctx;
    }

    private CapabilityRegistry started(List<String> crmProvides, List<String> posProvides, boolean lazy,
                                       Class<?>... components) {
        context = context(crmProvides, posProvides, lazy, components);
        context.refresh();
        return context.getBean(CapabilityRegistry.class);
    }

    /** The most specific cause of the refresh failure (the exception itself when it has no cause). */
    private Throwable refreshFailure() {
        Throwable thrown = catchThrowable(context::refresh);
        assertThat(thrown).as("refresh must fail").isNotNull();
        return NestedExceptionUtils.getMostSpecificCause(thrown);
    }

    @Test
    @DisplayName("class-annotated provider behind a JDK proxy → found, the proxy is returned")
    void jdkProxy() {
        CapabilityRegistry registry = started(List.of("GreetingPort"), List.of(), false,
                CrmGreeting.class, JdkProxying.class);
        Optional<GreetingPort> port = registry.find(GreetingPort.class, TENANT);
        assertThat(port).isPresent();
        assertThat(port.get().getClass().getName()).contains("$Proxy");
        assertThat(port.get().greet()).isEqualTo("crm");
    }

    @Test
    @DisplayName("@Bean-method provider (annotation on the factory method) → found")
    void beanMethodProvider() {
        CapabilityRegistry registry = started(List.of(), List.of("FarewellPort"), false, BeanMethodProvider.class);
        assertThat(registry.find(FarewellPort.class, TENANT)).map(FarewellPort::bye).contains("bye");
    }

    @Test
    @DisplayName("sub-capability: only the most specific interface is registered")
    void subCapability() {
        CapabilityRegistry registry = started(List.of("FancyGreetingPort"), List.of(), false, CrmFancyGreeting.class);
        assertThat(registry.find(FancyGreetingPort.class, TENANT)).isPresent();
        assertThat(registry.find(GreetingPort.class, TENANT)).isEmpty();
    }

    @Test
    @DisplayName("prototype or scoped-proxy provider → boot fails naming the bean")
    void nonSingletonProviders() {
        context = context(List.of("GreetingPort"), List.of(), false, PrototypeGreeting.class);
        assertThat(refreshFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bean 'capabilityRegistryContextTest.PrototypeGreeting'")
                .hasMessageContaining("must be singleton-scoped")
                .hasMessageNotContaining("no bean annotated");
        context = context(List.of("GreetingPort"), List.of(), false, ScopedProxyGreeting.class);
        assertThat(refreshFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bean 'capabilityRegistryContextTest.ScopedProxyGreeting'")
                .hasMessageContaining("must be singleton-scoped")
                .hasMessageNotContaining("scopedTarget.")
                .hasMessageNotContaining("no bean annotated");
    }

    @Test
    @DisplayName("injecting a provider directly fails at startup; the registry still finds it")
    void notInjectable() {
        context = context(List.of("GreetingPort"), List.of(), false, CrmGreeting.class, InjectingConsumer.class);
        assertThat(refreshFailure()).isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(GreetingPort.class.getName());

        CapabilityRegistry registry = started(List.of("GreetingPort"), List.of(), false, CrmGreeting.class);
        assertThat(context.getBeanDefinition("capabilityRegistryContextTest.CrmGreeting").isAutowireCandidate())
                .isFalse();
        assertThat(registry.find(GreetingPort.class, TENANT)).isPresent();
    }

    /** Annotated only through {@link CrmGreeting}. */
    public static class InheritedGreeting extends CrmGreeting {
    }

    @Test
    @DisplayName("annotation inherited from a superclass → boot fails naming the subclass, no duplicate provider")
    void inheritedAnnotation() {
        context = context(List.of("GreetingPort"), List.of(), false, CrmGreeting.class, InheritedGreeting.class);
        assertThat(refreshFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(InheritedGreeting.class.getName())
                .hasMessageContaining("@ProvidesCapability must be declared on the bean class itself")
                .hasMessageNotContaining("provided twice");
    }

    @Test
    @DisplayName("global lazy initialization + misconfiguration → the boot still fails (registry is @Lazy(false))")
    void lazyInitializationStillValidates() {
        context = context(List.of("GreetingPort"), List.of(), true); // manifest provides it, no bean
        assertThat(refreshFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lists 'GreetingPort' in 'provides'");
    }

    @Test
    @DisplayName("spring.main.lazy-initialization=true → initialized at the first find")
    void lazyInitialization() {
        CapabilityRegistry registry = started(List.of("GreetingPort"), List.of(), true, CrmGreeting.class);
        assertThat(registry.find(GreetingPort.class, TENANT)).map(GreetingPort::greet).contains("crm");
    }

    @Test
    @DisplayName("find from a consumer's init hook (before afterSingletonsInstantiated) → works, then idempotent")
    void initHook() {
        InitHookConsumer.found = null;
        CapabilityRegistry registry = started(List.of("GreetingPort"), List.of(), false,
                InitHookConsumer.class, CrmGreeting.class);
        assertThat(InitHookConsumer.found).isNotNull().isPresent();
        assertThat(registry.find(GreetingPort.class, TENANT)).containsSame(InitHookConsumer.found.get());
    }
}
