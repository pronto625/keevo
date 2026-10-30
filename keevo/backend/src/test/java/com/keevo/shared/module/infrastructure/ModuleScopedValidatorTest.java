package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleScoped;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.TestModuleCatalogs;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Story 13.9 — boot validation of {@link ModuleScoped}. */
class ModuleScopedValidatorTest {

    record Event(String tenantSchema) implements com.keevo.shared.event.catalog.TenantScopedEvent {
    }

    /** Listener inherited from a non-annotated base class, scope declared on the subclass. */
    static class BaseListener {
        @EventListener
        public void on(Event event) {
        }
    }

    @ModuleScoped("reporting")
    static class ScopedSubclass extends BaseListener {
    }

    @ModuleScoped("reporting")
    static class NotTenantScoped {
        @EventListener
        public void on(String payload) {
        }
    }

    @ModuleScoped("ghost")
    static class GhostBase {
        @EventListener
        public void on(Event event) {
        }
    }

    static class InheritsGhost extends GhostBase {
    }

    @ModuleScoped("reporting")
    static class Good {
        @EventListener
        public void on(Event event) {
        }
    }

    @ModuleScoped("ghost")
    static class UnknownModule {
        @EventListener
        public void on(Event event) {
        }
    }

    static class NotAListener {
        @ModuleScoped("reporting")
        public void handle(Event event) {
        }
    }

    @ModuleScoped("reporting")
    static class NoListenerAtAll {
        public void handle(Event event) {
        }
    }

    @SuppressWarnings("unchecked")
    private ModuleScopedValidator validator() {
        ModuleRegistry registry = new ModuleRegistryService(TestModuleCatalogs.testCatalog(),
                schema -> Optional.empty(), new RequestScopedActiveModulesCache(), Clock.systemUTC());
        ObjectProvider<ModuleRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        return new ModuleScopedValidator(provider);
    }

    @Test
    @DisplayName("installed id on a listener class → ok, before and after the singletons")
    void good() {
        ModuleScopedValidator validator = validator();
        validator.postProcessBeforeInitialization(new Good(), "good");
        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThatCode(() -> validator.postProcessBeforeInitialization(new Good(), "late"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("id not installed → boot fails naming the class")
    void unknownModule() {
        ModuleScopedValidator validator = validator();
        validator.postProcessBeforeInitialization(new UnknownModule(), "unknown");
        assertThatThrownBy(validator::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(UnknownModule.class.getName()).hasMessageContaining("'ghost'");
    }

    @Test
    @DisplayName("on a non-listener method, or a class without listener → refused")
    void misplaced() {
        assertThatThrownBy(() -> validator().postProcessBeforeInitialization(new NotAListener(), "x"))
                .isInstanceOf(BeanCreationException.class).hasMessageContaining("handle() is not an event listener");
        assertThatThrownBy(() -> validator().postProcessBeforeInitialization(new NoListenerAtAll(), "y"))
                .isInstanceOf(BeanCreationException.class).hasMessageContaining("declares no event-listener method");
    }

    @Test
    @DisplayName("inherited listener + subclass scope → ok; inherited scope with an unknown id → boot fails")
    void inherited() {
        ModuleScopedValidator validator = validator();
        assertThatCode(() -> validator.postProcessBeforeInitialization(new ScopedSubclass(), "sub"))
                .doesNotThrowAnyException();
        validator.postProcessBeforeInitialization(new InheritsGhost(), "ghostSub");
        assertThatThrownBy(validator::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'ghost'");
    }

    @Test
    @DisplayName("a gated listener whose event is not a TenantScopedEvent → refused")
    void notTenantScoped() {
        assertThatThrownBy(() -> validator().postProcessBeforeInitialization(new NotTenantScoped(), "z"))
                .isInstanceOf(BeanCreationException.class).hasMessageContaining("does not take a TenantScopedEvent");
    }
}
