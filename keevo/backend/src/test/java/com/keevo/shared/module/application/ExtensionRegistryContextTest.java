package com.keevo.shared.module.application;

import com.keevo.shared.module.ContributesExtension;
import com.keevo.shared.module.Extension;
import com.keevo.shared.module.ExtensionRegistry;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.infrastructure.ModuleBeanAutowireExclusion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.NestedExceptionUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 13.7 — {@link ExtensionRegistryService} in a real Spring container: discovery by bean name (class and
 * {@code @Bean} method), singleton-only contributions, contributions not injectable (single or collection), lazy
 * initialization.
 */
class ExtensionRegistryContextTest {

    private static final String TENANT = "kv_ctx002";
    private static final String SLOT = "pos.checkout.post-sale-actions";

    /** An unannotated base (an annotated superclass would be found on the subclass too). */
    public static class BaseAction implements Extension {
        @Override
        public String id() {
            return "invoicing.create-invoice";
        }

        @Override
        public String label() {
            return "Facturer";
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public String action() {
            return "invoicing.create-from-sale";
        }
    }

    @ContributesExtension(module = "invoicing", point = SLOT)
    public static class InvoiceAction extends BaseAction {
    }

    @ContributesExtension(module = "invoicing", point = SLOT)
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public static class PrototypeAction extends BaseAction {
    }

    @Configuration(proxyBeanMethods = false)
    public static class BeanMethodContribution {
        @Bean
        @ContributesExtension(module = "crm", point = SLOT)
        Extension loyalty() {
            return new BaseAction() {
                @Override
                public String id() {
                    return "crm.loyalty";
                }

                @Override
                public int order() {
                    return 10;
                }

                @Override
                public String action() {
                    return "crm.add-loyalty";
                }
            };
        }
    }

    /** Annotated only through {@link InvoiceAction}. */
    public static class InheritedAction extends InvoiceAction {
    }

    public static class InjectingConsumer {
        public InjectingConsumer(Extension extension) {
        }
    }

    public static class ListConsumer {
        static volatile List<Extension> injected;

        public ListConsumer(List<Extension> extensions) {
            injected = extensions;
        }
    }

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private AnnotationConfigApplicationContext context(List<String> invoicingFills, List<String> crmFills,
                                                       boolean lazy, Class<?>... components) {
        ModuleRegistry registry = mock(ModuleRegistry.class);
        when(registry.isKnownTenant(TENANT)).thenReturn(true);
        when(registry.moduleState(eq(TENANT), anyString())).thenReturn(ModuleState.ENABLED);
        ModuleCatalog catalog = new ModuleCatalog(List.of(
                TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withExtensionPoints("pos", List.of(SLOT), List.of()),
                TestModuleCatalogs.withExtensionPoints("invoicing", List.of(), invoicingFills),
                TestModuleCatalogs.withExtensionPoints("crm", List.of(), crmFills)));
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        if (lazy) {
            ctx.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
        }
        ctx.registerBean(ModuleRegistry.class, () -> registry);
        ctx.registerBean(ModuleCatalog.class, () -> catalog);
        ctx.register(ExtensionRegistryService.class, ModuleBeanAutowireExclusion.class);
        if (components.length > 0) {
            ctx.register(components);
        }
        return ctx;
    }

    private ExtensionRegistry started(List<String> invoicingFills, List<String> crmFills, boolean lazy,
                                      Class<?>... components) {
        context = context(invoicingFills, crmFills, lazy, components);
        context.refresh();
        return context.getBean(ExtensionRegistry.class);
    }

    private Throwable refreshFailure() {
        Throwable thrown = catchThrowable(context::refresh);
        assertThat(thrown).as("refresh must fail").isNotNull();
        return NestedExceptionUtils.getMostSpecificCause(thrown);
    }

    @Test
    @DisplayName("class-annotated and @Bean-method contributions → found and sorted")
    void discovery() {
        ExtensionRegistry registry = started(List.of(SLOT), List.of(SLOT), false,
                InvoiceAction.class, BeanMethodContribution.class);
        assertThat(registry.contributions(SLOT, TENANT)).extracting(Extension::id)
                .containsExactly("crm.loyalty", "invoicing.create-invoice");
    }

    @Test
    @DisplayName("prototype contribution → boot fails naming the bean")
    void prototype() {
        context = context(List.of(SLOT), List.of(), false, PrototypeAction.class);
        assertThat(refreshFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bean 'extensionRegistryContextTest.PrototypeAction'")
                .hasMessageContaining("must be singleton-scoped")
                .hasMessageNotContaining("contributes to it");
    }

    @Test
    @DisplayName("injecting a contribution fails at startup, a collection gets none; the registry still finds them")
    void notInjectable() {
        context = context(List.of(SLOT), List.of(), false, InvoiceAction.class, InjectingConsumer.class);
        assertThat(refreshFailure()).isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(Extension.class.getName());

        ListConsumer.injected = null;
        context = context(List.of(SLOT), List.of(), false, InvoiceAction.class, ListConsumer.class);
        context.refresh();
        assertThat(ListConsumer.injected).as("a List<Extension> injection gets none of them").isEmpty();
        context.close();

        ExtensionRegistry registry = started(List.of(SLOT), List.of(), false, InvoiceAction.class);
        assertThat(context.getBeanDefinition("extensionRegistryContextTest.InvoiceAction").isAutowireCandidate())
                .isFalse();
        assertThat(registry.contributions(SLOT, TENANT)).hasSize(1);
    }

    @Test
    @DisplayName("a @Bean-method contribution is not injectable either; the registry finds it")
    void beanMethodNotInjectable() {
        context = context(List.of(), List.of(SLOT), false, BeanMethodContribution.class, InjectingConsumer.class);
        assertThat(refreshFailure()).isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(Extension.class.getName());

        ExtensionRegistry registry = started(List.of(), List.of(SLOT), false, BeanMethodContribution.class);
        assertThat(context.getBeanDefinition("loyalty").isAutowireCandidate()).isFalse();
        assertThat(registry.contributions(SLOT, TENANT)).extracting(Extension::id).containsExactly("crm.loyalty");
    }

    @Test
    @DisplayName("annotation inherited from a superclass → boot fails naming the subclass, no double registration")
    void inheritedAnnotation() {
        context = context(List.of(SLOT), List.of(), false, InvoiceAction.class, InheritedAction.class);
        assertThat(refreshFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(InheritedAction.class.getName())
                .hasMessageContaining("must be declared on the bean class itself")
                .hasMessageNotContaining("contributed twice");
    }

    @Test
    @DisplayName("global lazy initialization + misconfiguration → the boot still fails (registry is @Lazy(false))")
    void lazyInitializationStillValidates() {
        context = context(List.of(SLOT), List.of(), true); // invoicing fills the slot, no bean
        assertThat(refreshFailure()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("module 'invoicing' lists '" + SLOT + "'");
    }

    @Test
    @DisplayName("spring.main.lazy-initialization=true → initialized at the first call")
    void lazyInitialization() {
        ExtensionRegistry registry = started(List.of(SLOT), List.of(), true, InvoiceAction.class);
        assertThat(registry.contributions(SLOT, TENANT)).extracting(Extension::label).containsExactly("Facturer");
    }
}
