package com.keevo.shared.module.application;

import com.keevo.shared.module.KeevoModule;
import com.keevo.shared.module.TestModuleCatalogs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 13.9 — discovery and boot validation of the {@link KeevoModule} beans. */
class KeevoModuleDirectoryTest {

    record Module(String id) implements KeevoModule {
    }

    static final class OtherCrm implements KeevoModule {
        @Override
        public String id() {
            return "crm";
        }
    }

    private final ModuleCatalog catalog = TestModuleCatalogs.testCatalog(); // admin, core, crm, invoicing, pos, ...

    @Test
    @DisplayName("one bean per installed id → indexed; a manifest without bean is fine")
    void valid() {
        Module crm = new Module("crm");
        KeevoModuleDirectory directory = KeevoModuleDirectory.of(catalog, List.of(crm, new Module("pos")));
        assertThat(directory.module("crm")).containsSame(crm);
        assertThat(directory.module("reporting")).isEmpty();
        assertThat(directory.module(null)).isEmpty();
        assertThat(directory.all()).extracting(KeevoModule::id).containsExactly("crm", "pos");
        assertThat(KeevoModuleDirectory.empty(catalog).all()).isEmpty();
    }

    @Test
    @DisplayName("two beans with the same id → boot fails naming both classes")
    void duplicateId() {
        assertThatThrownBy(() -> KeevoModuleDirectory.of(catalog, List.of(new Module("crm"), new OtherCrm())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("module 'crm' is implemented by several KeevoModule beans")
                .hasMessageContaining(OtherCrm.class.getName()).hasMessageContaining(Module.class.getName());
    }

    @Test
    @DisplayName("bean id not installed, or blank → boot fails")
    void notInstalled() {
        assertThatThrownBy(() -> KeevoModuleDirectory.of(catalog, List.of(new Module("ghost"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("module 'ghost', which is not installed");
        assertThatThrownBy(() -> KeevoModuleDirectory.of(catalog, List.of(new Module(" "))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("blank module id");
    }

    @Test
    @DisplayName("Spring context: the beans are discovered at startup; a duplicate fails the refresh")
    void springDiscovery() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ModuleCatalog.class, () -> catalog);
            context.registerBean("crmModule", KeevoModule.class, () -> new Module("crm"));
            context.registerBean(KeevoModuleDirectory.class);
            context.refresh();
            assertThat(context.getBean(KeevoModuleDirectory.class).module("crm")).isPresent();
        }
        AnnotationConfigApplicationContext broken = new AnnotationConfigApplicationContext();
        broken.registerBean(ModuleCatalog.class, () -> catalog);
        broken.registerBean("crmModule", KeevoModule.class, () -> new Module("crm"));
        broken.registerBean("otherCrm", KeevoModule.class, OtherCrm::new);
        broken.registerBean(KeevoModuleDirectory.class);
        assertThatThrownBy(broken::refresh).hasStackTraceContaining("implemented by several KeevoModule beans");
        broken.close();
    }
}
