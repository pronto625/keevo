package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.RequiresEntitlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;

import static com.keevo.shared.module.TestModuleCatalogs.testCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 13.5 — startup checks of {@link RequiresEntitlement}: unknown module ids, and every placement the aspect's
 * pointcut cannot advise (it would leave the gate silently open). Each failure names the class.
 */
class RequiresEntitlementValidatorTest {

    // ── accepted placements ─────────────────────────────────────────────────────────────────────────

    @RequiresEntitlement("invoicing")
    static class KnownOnType {
        public void write() {
        }

        @Override
        public String toString() {
            return "ok"; // Object methods are excluded by the pointcut: allowed
        }
    }

    static class KnownOnMethod {
        @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
        public void read() {
        }
    }

    // Classes carrying @Scheduled are abstract: SchedulerLockCoverageTest scans concrete @Scheduled classes of
    // com.keevo (test classes included); placementProblems() works on the class alone.

    abstract static class GatedNextToUngatedScheduled {
        @RequiresEntitlement("crm")
        public void read() {
        }

        @Scheduled(fixedDelay = 1000)
        public void tick() { // not gated: fine next to a gated method
        }
    }

    // ── unknown ids ─────────────────────────────────────────────────────────────────────────────────

    static class TypoOnMethod {
        @RequiresEntitlement("invoicng")
        public void write() {
        }
    }

    @RequiresEntitlement("ghost")
    static class TypoOnType {
    }

    // ── misplacements ───────────────────────────────────────────────────────────────────────────────

    @RequiresEntitlement("crm")
    interface GatedApi {
        void write();
    }

    static class ImplementsAnnotatedInterface implements GatedApi {
        @Override
        public void write() {
        }
    }

    interface ApiWithGatedMethod {
        @RequiresEntitlement("crm")
        void write();
    }

    static class ImplementsAnnotatedInterfaceMethod implements ApiWithGatedMethod {
        @Override
        public void write() {
        }
    }

    abstract static class AbstractBase {
        @RequiresEntitlement("crm")
        public abstract void write();
    }

    static class OverridesAnnotatedAbstractMethod extends AbstractBase {
        @Override
        public void write() {
        }
    }

    @RequiresEntitlement("crm")
    static class AnnotatedSuperclass {
        public void write() {
        }
    }

    static class ExtendsAnnotatedSuperclass extends AnnotatedSuperclass {
    }

    static class PlainBase {
        public void inherited() {
        }
    }

    @RequiresEntitlement("crm")
    static class ClassLevelInheritingMethods extends PlainBase {
        public void own() {
        }
    }

    static class NonProxiableMethods {
        @RequiresEntitlement("crm")
        public final void finalMethod() {
        }

        @RequiresEntitlement("crm")
        private void privateMethod() {
        }

        @RequiresEntitlement("crm")
        public static void staticMethod() {
        }

        @RequiresEntitlement("crm")
        void packageMethod() {
        }
    }

    @RequiresEntitlement("crm")
    static class ClassLevelWithPublicFinal {
        public final void write() {
        }
    }

    static class GatedHandlerInfrastructure {
        @RequiresEntitlement("crm")
        @ExceptionHandler(IllegalStateException.class)
        public void onError() {
        }

        @RequiresEntitlement("crm")
        @InitBinder
        public void binder() {
        }

        @RequiresEntitlement("crm")
        @ModelAttribute
        public void attribute() {
        }
    }

    abstract static class GatedScheduledMethod {
        @RequiresEntitlement("crm")
        @Scheduled(fixedDelay = 1000)
        public void tick() {
        }
    }

    @RequiresEntitlement("crm")
    abstract static class ClassLevelWithScheduled {
        @Scheduled(fixedDelay = 1000)
        public void tick() {
        }
    }

    @RequiresEntitlement("crm")
    static class ClassLevelWithEventListener {
        @EventListener
        public void on(Object event) {
        }
    }

    @RequiresEntitlement("crm")
    static class ClassLevelWithTransactionalEventListener {
        @TransactionalEventListener
        public void on(Object event) {
        }
    }

    @RequiresEntitlement("crm")
    static class ClassLevelWithModuleListener {
        @ApplicationModuleListener
        public void on(Object event) {
        }
    }

    private static ModuleRegistry registry() {
        ModuleRegistry registry = mock(ModuleRegistry.class);
        when(registry.definition(anyString())).thenAnswer(inv -> testCatalog().find(inv.getArgument(0)));
        return registry;
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ModuleRegistry.class, RequiresEntitlementValidatorTest::registry)
            .withBean(RequiresEntitlementValidator.class);

    private static void assertRefused(Class<?> type, String... fragments) {
        assertThat(RequiresEntitlementValidator.placementProblems(type)).as(type.getSimpleName()).isNotEmpty()
                .anySatisfy(p -> assertThat(p).contains(fragments));
    }

    @Test
    @DisplayName("installed ids, accepted placements (Object override, ungated @Scheduled next to a gated method) → starts")
    void knownIds() {
        assertThat(RequiresEntitlementValidator.placementProblems(KnownOnType.class)).isEmpty();
        assertThat(RequiresEntitlementValidator.placementProblems(KnownOnMethod.class)).isEmpty();
        assertThat(RequiresEntitlementValidator.placementProblems(GatedNextToUngatedScheduled.class)).isEmpty();
        runner.withBean(KnownOnType.class).withBean(KnownOnMethod.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("unknown id on a method → startup fails, message names the class and the id")
    void typoOnMethod() {
        runner.withBean(TypoOnMethod.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TypoOnMethod.class.getName())
                    .hasMessageContaining("'invoicng'");
        });
    }

    @Test
    @DisplayName("unknown id on a type → startup fails")
    void typoOnType() {
        runner.withBean(TypoOnType.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining(TypoOnType.class.getName()).hasMessageContaining("'ghost'");
        });
    }

    @Test
    @DisplayName("annotation on an interface, an interface method, an abstract base method or a superclass → refused")
    void supertypePlacements() {
        assertRefused(ImplementsAnnotatedInterface.class, "supertype", GatedApi.class.getName());
        assertRefused(ImplementsAnnotatedInterfaceMethod.class, "ApiWithGatedMethod.write()");
        assertRefused(OverridesAnnotatedAbstractMethod.class, "AbstractBase.write()");
        assertRefused(ExtendsAnnotatedSuperclass.class, "supertype", AnnotatedSuperclass.class.getName());
    }

    @Test
    @DisplayName("class-level annotation with methods inherited from an unannotated superclass → refused")
    void classLevelInheritedMethods() {
        assertRefused(ClassLevelInheritingMethods.class, "inherited()", PlainBase.class.getName());
    }

    @Test
    @DisplayName("final, private, static or package-private annotated methods; public final under class level → refused")
    void nonProxiableMethods() {
        for (String m : new String[] {"finalMethod()", "privateMethod()", "staticMethod()", "packageMethod()"}) {
            assertRefused(NonProxiableMethods.class, m, "public, non-static and non-final");
        }
        assertRefused(ClassLevelWithPublicFinal.class, "public final write()");
    }

    @Test
    @DisplayName("annotated @ExceptionHandler / @InitBinder / @ModelAttribute methods → refused (excluded by the pointcut)")
    void handlerInfrastructure() {
        for (String m : new String[] {"onError()", "binder()", "attribute()"}) {
            assertRefused(GatedHandlerInfrastructure.class, m, "handler infrastructure");
        }
    }

    @Test
    @DisplayName("tenant-less methods: annotated @Scheduled; class level with @Scheduled / @EventListener / "
            + "@TransactionalEventListener / @ApplicationModuleListener → refused")
    void tenantlessMethods() {
        assertRefused(GatedScheduledMethod.class, "tick()", "without a tenant");
        assertRefused(ClassLevelWithScheduled.class, "tick()", "without a tenant");
        assertRefused(ClassLevelWithEventListener.class, "on()", "without a tenant");
        assertRefused(ClassLevelWithTransactionalEventListener.class, "on()", "without a tenant");
        assertRefused(ClassLevelWithModuleListener.class, "on()", "without a tenant");
    }

    @Test
    @DisplayName("a misplacement fails the startup, naming the bean class")
    void misplacementFailsStartup() {
        runner.withBean(ImplementsAnnotatedInterfaceMethod.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(
                    ImplementsAnnotatedInterfaceMethod.class.getName());
        });
    }

    @Test
    @DisplayName("moduleIds() collects the ids declared on the type and its own methods")
    void moduleIdsScan() {
        assertThat(RequiresEntitlementValidator.moduleIds(KnownOnType.class)).containsExactly("invoicing");
        assertThat(RequiresEntitlementValidator.moduleIds(KnownOnMethod.class)).containsExactly("crm");
        assertThat(RequiresEntitlementValidator.moduleIds(Object.class)).isEmpty();
    }
}
