package com.keevo.shared.module.application;

import com.keevo.shared.module.Capability;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ProvidesCapability;
import com.keevo.shared.module.TestModuleCatalogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 13.6 — tenant-aware resolution and boot validation of {@code CapabilityRegistryService}. */
class CapabilityRegistryServiceTest {

    private static final String TENANT = "kv_abc123";

    interface NameLookupPort extends Capability {
        String name();
    }

    interface PriceLookupPort extends Capability {
    }

    interface UnprovidedPort extends Capability {
    }

    interface NotACapability {
    }

    interface SubNameLookupPort extends NameLookupPort {
    }

    @ProvidesCapability(module = "crm")
    static class CrmSubNames implements SubNameLookupPort {
        @Override
        public String name() {
            return "sub";
        }
    }

    static final class First {
        interface SamePort extends Capability {
        }
    }

    static final class Second {
        interface SamePort extends Capability {
        }
    }

    @ProvidesCapability(module = "crm")
    static class TwoSameNames implements First.SamePort, Second.SamePort {
    }

    @ProvidesCapability(module = "crm")
    static class CrmNames implements NameLookupPort {
        @Override
        public String name() {
            return "crm";
        }
    }

    @ProvidesCapability(module = "pos")
    static class PosNames implements NameLookupPort {
        @Override
        public String name() {
            return "pos";
        }
    }

    @ProvidesCapability(module = "ghost")
    static class GhostNames extends CrmNames {
    }

    @ProvidesCapability(module = "crm")
    static class NoCapability implements NotACapability {
    }

    @ProvidesCapability(module = "crm")
    static class MarkerOnly implements Capability {
    }

    abstract static class AbstractCapability implements Capability {
    }

    @ProvidesCapability(module = "crm", value = AbstractCapability.class)
    static class DeclaresAClass extends AbstractCapability {
    }

    @ProvidesCapability(module = "crm", value = PriceLookupPort.class)
    static class DeclaresUnimplemented implements NameLookupPort {
        @Override
        public String name() {
            return "x";
        }
    }

    @ProvidesCapability(module = "crm", value = NameLookupPort.class)
    static class NamesAndPrices implements NameLookupPort, PriceLookupPort {
        @Override
        public String name() {
            return "both";
        }
    }

    @ProvidesCapability(module = "crm")
    interface AnnotatedNameLookup extends NameLookupPort {
    }

    static class ViaAnnotatedInterface implements AnnotatedNameLookup {
        @Override
        public String name() {
            return "interface";
        }
    }

    static class InheritsCrmNames extends CrmNames {
    }

    static class NotAnnotated implements NameLookupPort {
        @Override
        public String name() {
            return "none";
        }
    }

    private ModuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = mock(ModuleRegistry.class);
    }

    private static ModuleCatalog catalog(List<String> crmProvides, List<String> posProvides) {
        return new ModuleCatalog(List.of(
                TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withCapabilities("crm", crmProvides, List.of()),
                TestModuleCatalogs.withCapabilities("pos", posProvides, List.of())));
    }

    private CapabilityRegistryService service(ModuleCatalog catalog, Object... providers) {
        CapabilityRegistryService service = new CapabilityRegistryService(registry, catalog, null);
        service.initialize(List.of(providers));
        return service;
    }

    private CapabilityRegistryService crmProvidingNames() {
        return service(catalog(List.of("NameLookupPort"), List.of()), new CrmNames());
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("provider ENABLED → the implementation")
        void enabled() {
            when(registry.moduleState(TENANT, "crm")).thenReturn(ModuleState.ENABLED);
            CapabilityRegistryService service = crmProvidingNames();
            assertThat(service.find(NameLookupPort.class, TENANT)).map(NameLookupPort::name).contains("crm");
            assertThat(service.has(NameLookupPort.class, TENANT)).isTrue();
        }

        @Test
        @DisplayName("provider READ_ONLY → the implementation (lookups stay available)")
        void readOnly() {
            when(registry.moduleState(TENANT, "crm")).thenReturn(ModuleState.READ_ONLY);
            assertThat(crmProvidingNames().find(NameLookupPort.class, TENANT)).isPresent();
        }

        @Test
        @DisplayName("provider module DISABLED for the tenant → empty, no exception")
        void disabled() {
            when(registry.moduleState(TENANT, "crm")).thenReturn(ModuleState.DISABLED);
            CapabilityRegistryService service = crmProvidingNames();
            assertThat(service.find(NameLookupPort.class, TENANT)).isEmpty();
            assertThat(service.has(NameLookupPort.class, TENANT)).isFalse();
        }

        @Test
        @DisplayName("platform-admin schema → empty, the registry is not asked")
        void platformSchema() {
            CapabilityRegistryService service = crmProvidingNames();
            assertThat(service.find(NameLookupPort.class, "public")).isEmpty();
            verify(registry, never()).moduleState(anyString(), anyString());
        }

        @Test
        @DisplayName("no provider for the type in this build → empty")
        void noProvider() {
            assertThat(crmProvidingNames().find(UnprovidedPort.class, TENANT)).isEmpty();
            verify(registry, never()).moduleState(anyString(), anyString());
        }

        @Test
        @DisplayName("invalid schema string → IllegalArgumentException, even for an unprovided type")
        void invalidSchema() {
            CapabilityRegistryService service = crmProvidingNames();
            assertThatThrownBy(() -> service.find(UnprovidedPort.class, "x; DROP"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.find(NameLookupPort.class, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("used before initialization → IllegalStateException; initialized twice → IllegalStateException")
        void lifecycle() {
            CapabilityRegistryService service = new CapabilityRegistryService(registry, catalog(List.of(), List.of()), null);
            assertThatThrownBy(() -> service.find(NameLookupPort.class, TENANT)).isInstanceOf(IllegalStateException.class);
            service.initialize(List.of());
            assertThatThrownBy(() -> service.initialize(List.of())).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("annotation only on an interface or a superclass → boot fails naming the class; failure cached")
        void inheritedAnnotation() {
            CapabilityRegistryService service = new CapabilityRegistryService(registry,
                    catalog(List.of("NameLookupPort"), List.of()), null);
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.initialize(List.of(new ViaAnnotatedInterface(), new InheritsCrmNames())));
            assertThat(failure).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ViaAnnotatedInterface.class.getName())
                    .hasMessageContaining(InheritsCrmNames.class.getName())
                    .hasMessageContaining("@ProvidesCapability must be declared on the bean class itself")
                    .hasMessageNotContaining("no bean annotated");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.find(NameLookupPort.class, TENANT)))
                    .isSameAs(failure);
        }

        @Test
        @DisplayName("an AOP-proxied provider is indexed by its target class and the proxy is returned")
        void proxiedProvider() {
            ProxyFactory factory = new ProxyFactory(new CrmNames());
            factory.setProxyTargetClass(true);
            Object proxy = factory.getProxy();
            when(registry.moduleState(TENANT, "crm")).thenReturn(ModuleState.ENABLED);
            CapabilityRegistryService service = service(catalog(List.of("NameLookupPort"), List.of()), proxy);
            assertThat(service.find(NameLookupPort.class, TENANT)).containsSame((NameLookupPort) proxy);
        }

        @Test
        @DisplayName("implicit types: only the most specific capability interface is registered")
        void mostSpecificOnly() {
            when(registry.moduleState(TENANT, "crm")).thenReturn(ModuleState.ENABLED);
            CapabilityRegistryService service = service(catalog(List.of("SubNameLookupPort"), List.of()),
                    new CrmSubNames());
            assertThat(service.find(SubNameLookupPort.class, TENANT)).isPresent();
            assertThat(service.find(NameLookupPort.class, TENANT)).isEmpty();
        }

        @Test
        @DisplayName("explicit value() limits the provided types")
        void explicitTypes() {
            when(registry.moduleState(TENANT, "crm")).thenReturn(ModuleState.ENABLED);
            CapabilityRegistryService service = service(catalog(List.of("NameLookupPort"), List.of()),
                    new NamesAndPrices());
            assertThat(service.find(NameLookupPort.class, TENANT)).isPresent();
            assertThat(service.find(PriceLookupPort.class, TENANT)).isEmpty();
        }
    }

    @Nested
    @DisplayName("boot validation")
    class BootValidation {

        private void assertBootFails(ModuleCatalog catalog, String expected, Object... providers) {
            assertThatThrownBy(() -> service(catalog, providers))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(expected);
        }

        @Test
        @DisplayName("provider module not installed")
        void moduleNotInstalled() {
            assertBootFails(catalog(List.of(), List.of()), GhostNames.class.getName()
                    + " provides for module 'ghost', which is not installed", new GhostNames());
        }

        @Test
        @DisplayName("two providers for the same type")
        void twoProviders() {
            assertBootFails(catalog(List.of("NameLookupPort"), List.of("NameLookupPort")),
                    "capability " + NameLookupPort.class.getName() + " provided twice: by "
                            + CrmNames.class.getName() + " (module 'crm') and " + PosNames.class.getName(),
                    new PosNames(), new CrmNames());
        }

        @Test
        @DisplayName("the skipped duplicate does not also report its manifest entry as unprovided")
        void duplicateReportedOnce() {
            assertThatThrownBy(() -> service(catalog(List.of("NameLookupPort"), List.of("NameLookupPort")),
                    new CrmNames(), new PosNames()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("provided twice")
                    .hasMessageNotContaining("no bean annotated");
        }

        @Test
        @DisplayName("two capabilities with the same simple name in one module")
        void sameSimpleNameInOneModule() {
            assertBootFails(catalog(List.of("SamePort"), List.of()), "module 'crm' provides two capabilities named "
                    + "'SamePort': " + First.SamePort.class.getName() + " and " + Second.SamePort.class.getName(),
                    new TwoSameNames());
        }

        @Test
        @DisplayName("declared type is not an interface extending Capability")
        void declaredClass() {
            assertBootFails(catalog(List.of(), List.of()), DeclaresAClass.class.getName() + " declares "
                    + AbstractCapability.class.getName() + ", which is not an interface", new DeclaresAClass());
        }

        @Test
        @DisplayName("declared type not implemented by the bean")
        void declaredUnimplemented() {
            assertBootFails(catalog(List.of(), List.of()), "does not implement it", new DeclaresUnimplemented());
        }

        @Test
        @DisplayName("bean implementing no capability interface (none at all, or only the marker)")
        void noCapabilityInterface() {
            assertBootFails(catalog(List.of(), List.of()), NoCapability.class.getName()
                    + " (module 'crm') implements no capability interface", new NoCapability());
            assertBootFails(catalog(List.of(), List.of()), MarkerOnly.class.getName()
                    + " (module 'crm') implements no capability interface", new MarkerOnly());
        }

        @Test
        @DisplayName("bean whose capability is missing from its manifest 'provides'")
        void missingManifestEntry() {
            assertBootFails(catalog(List.of(), List.of()), "the manifest of module 'crm' does not list "
                    + "'NameLookupPort' in 'provides'", new CrmNames());
        }

        @Test
        @DisplayName("manifest 'provides' entry without provider bean")
        void entryWithoutBean() {
            assertBootFails(catalog(List.of("NameLookupPort", "PriceLookupPort"), List.of()),
                    "the manifest of module 'crm' lists 'PriceLookupPort' in 'provides' but no bean",
                    new CrmNames());
        }

        @Test
        @DisplayName("bean without the annotation")
        void notAnnotated() {
            assertBootFails(catalog(List.of(), List.of()), NotAnnotated.class.getName()
                    + " is not annotated @ProvidesCapability", new NotAnnotated());
        }

        @Test
        @DisplayName("a failed validation indexes nothing")
        void nothingIndexedOnFailure() {
            CapabilityRegistryService service = new CapabilityRegistryService(registry,
                    catalog(List.of("NameLookupPort", "PriceLookupPort"), List.of()), null);
            assertThatThrownBy(() -> service.initialize(List.of(new CrmNames())))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.find(NameLookupPort.class, TENANT))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
