package com.keevo.shared.module.application;

import com.keevo.shared.module.ContributesExtension;
import com.keevo.shared.module.Extension;
import com.keevo.shared.module.ExtensionView;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.TestModuleCatalogs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 13.7 — tenant-aware contributions and boot validation of {@code ExtensionRegistryService}. */
class ExtensionRegistryServiceTest {

    private static final String TENANT = "kv_ext001";
    private static final String SLOT = "pos.checkout.post-sale-actions";
    private static final String OTHER_SLOT = "pos.checkout.pre-sale-actions";

    /** A constant contribution. */
    abstract static class Action implements Extension {
        private final String id;
        private final String label;
        private final int order;
        private final String action;

        Action(String id, String label, int order, String action) {
            this.id = id;
            this.label = label;
            this.order = order;
            this.action = action;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public String action() {
            return action;
        }
    }

    @ContributesExtension(module = "invoicing", point = SLOT)
    static class Invoice extends Action {
        Invoice() {
            super("invoicing.create-invoice", "Facturer", 20, "invoicing.create-from-sale");
        }
    }

    @ContributesExtension(module = "crm", point = SLOT)
    static class Loyalty extends Action {
        Loyalty() {
            super("crm.loyalty", "Ajouter des points de fidélité", 10, "crm.add-loyalty");
        }
    }

    @ContributesExtension(module = "crm", point = OTHER_SLOT)
    static class CrmOtherSlot extends Action {
        CrmOtherSlot() {
            super("crm.loyalty", "Choisir le client", 5, "crm.pick-client");
        }
    }

    @ContributesExtension(module = "crm", point = SLOT)
    static class SameIdAsLoyalty extends Action {
        SameIdAsLoyalty() {
            super("crm.loyalty", "Autre", 30, "crm.other");
        }
    }

    @ContributesExtension(module = "crm", point = SLOT)
    static class SameOrderZ extends Action {
        SameOrderZ() {
            super("crm.z-last", "Dernier", 20, "crm.same");
        }
    }

    @ContributesExtension(module = "crm", point = SLOT)
    static class BadKeys extends Action {
        BadKeys() {
            super("invoicing.create-invoice", "Clés", 1, "crm.trailing-");
        }
    }

    /** Its label changes after startup. */
    @ContributesExtension(module = "invoicing", point = SLOT)
    static class Mutable implements Extension {
        volatile String label = "Facturer";

        @Override
        public String id() {
            return "invoicing.mutable";
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int order() {
            return 1;
        }

        @Override
        public String action() {
            return "invoicing.create-from-sale";
        }
    }

    /** Annotated only through its superclass. */
    static class InheritsAnnotation extends Invoice {
    }

    @ContributesExtension(module = "invoicing", point = SLOT)
    interface AnnotatedContribution extends Extension {
    }

    /** Annotated only through an interface. */
    static class ViaInterface extends Action implements AnnotatedContribution {
        ViaInterface() {
            super("invoicing.via-interface", "Interface", 1, "invoicing.x");
        }
    }

    @ContributesExtension(module = "crm", point = SLOT)
    static class SameOrderAsInvoice extends Action {
        SameOrderAsInvoice() {
            super("crm.a-first", "Même rang", 20, "crm.same");
        }
    }

    @ContributesExtension(module = "crm", point = SLOT)
    static class Blank extends Action {
        Blank() {
            super(" ", " ", 1, "");
        }
    }

    @ContributesExtension(module = "ghost", point = SLOT)
    static class GhostModule extends Action {
        GhostModule() {
            super("ghost.x", "Fantôme", 1, "ghost.x");
        }
    }

    @ContributesExtension(module = "store", point = SLOT)
    static class NotInFills extends Action {
        NotInFills() {
            super("store.x", "Boutique", 1, "store.x");
        }
    }

    @ContributesExtension(module = "crm", point = SLOT)
    static class NotAnExtension {
    }

    /** Story 14.5 — a contribution requiring a module permission. */
    @ContributesExtension(module = "invoicing", point = SLOT)
    static class Guarded extends Action {
        private final String permission;

        Guarded(String permission) {
            super("invoicing.guarded", "Facturer", 20, "invoicing.open-sale-invoice");
            this.permission = permission;
        }

        @Override
        public String permission() {
            return permission;
        }
    }

    static class NotAnnotated extends Action {
        NotAnnotated() {
            super("crm.none", "Rien", 1, "crm.none");
        }
    }

    private ModuleRegistry registry;
    private final Map<String, ModuleState> states = new HashMap<>();

    @BeforeEach
    void setUp() {
        registry = mock(ModuleRegistry.class);
        when(registry.isKnownTenant(TENANT)).thenReturn(true);
        when(registry.moduleState(eq(TENANT), anyString()))
                .thenAnswer(call -> states.getOrDefault(call.<String>getArgument(1), ModuleState.DISABLED));
    }

    /** core; pos declares SLOT + OTHER_SLOT; invoicing and crm fill them; store fills nothing. */
    private static ModuleCatalog catalog(List<String> invoicingFills, List<String> crmFills) {
        return new ModuleCatalog(List.of(
                TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withExtensionPoints("pos", List.of(SLOT, OTHER_SLOT), List.of()),
                TestModuleCatalogs.withExtensionPoints("invoicing", List.of(), invoicingFills),
                TestModuleCatalogs.withExtensionPoints("crm", List.of(), crmFills),
                TestModuleCatalogs.withExtensionPoints("store", List.of(), List.of())));
    }

    private ExtensionRegistryService service(ModuleCatalog catalog, Object... contributions) {
        ExtensionRegistryService service = new ExtensionRegistryService(registry, catalog, null);
        service.initialize(List.of(contributions));
        return service;
    }

    private ExtensionRegistryService twoContributors() {
        return service(catalog(List.of(SLOT), List.of(SLOT)), new Invoice(), new Loyalty());
    }

    private void enabled(String... modules) {
        for (String module : modules) {
            states.put(module, ModuleState.ENABLED);
        }
    }

    private static List<String> ids(List<Extension> extensions) {
        return extensions.stream().map(Extension::id).toList();
    }

    private String bootFailure(ModuleCatalog catalog, Object... contributions) {
        ExtensionRegistryService service = new ExtensionRegistryService(registry, catalog, null);
        Throwable thrown = catchThrowable(() -> service.initialize(List.of(contributions)));
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid extension contributions: ");
        return thrown.getMessage();
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("declarer and contributor ENABLED → its extension listed")
        void activeContributor() {
            enabled("pos", "invoicing");
            List<Extension> listed = twoContributors().contributions(SLOT, TENANT);
            assertThat(listed).singleElement().satisfies(e -> {
                assertThat(e.label()).isEqualTo("Facturer");
                assertThat(e.action()).isEqualTo("invoicing.create-from-sale");
            });
        }

        @Test
        @DisplayName("contributor READ_ONLY → not listed (contributions are actions)")
        void contributorReadOnly() {
            enabled("pos", "crm");
            states.put("invoicing", ModuleState.READ_ONLY);
            assertThat(ids(twoContributors().contributions(SLOT, TENANT))).containsExactly("crm.loyalty");
        }

        @Test
        @DisplayName("contributor DISABLED → not listed")
        void contributorDisabled() {
            enabled("pos", "crm");
            states.put("invoicing", ModuleState.DISABLED);
            assertThat(ids(twoContributors().contributions(SLOT, TENANT))).containsExactly("crm.loyalty");
        }

        @Test
        @DisplayName("declaring module DISABLED → empty, contributors not asked")
        void declarerDisabled() {
            enabled("invoicing", "crm");
            states.put("pos", ModuleState.DISABLED);
            assertThat(twoContributors().contributions(SLOT, TENANT)).isEmpty();
            verify(registry, never()).moduleState(TENANT, "invoicing");
        }

        @Test
        @DisplayName("declaring module READ_ONLY → the slot still lists the active contributions")
        void declarerReadOnly() {
            enabled("invoicing");
            states.put("pos", ModuleState.READ_ONLY);
            assertThat(ids(twoContributors().contributions(SLOT, TENANT))).containsExactly("invoicing.create-invoice");
        }

        @Test
        @DisplayName("two contributors with orders 20 and 10 → sorted 10, 20; ties by module id then id")
        void sorted() {
            enabled("pos", "invoicing", "crm");
            assertThat(ids(twoContributors().contributions(SLOT, TENANT)))
                    .containsExactly("crm.loyalty", "invoicing.create-invoice");
            ExtensionRegistryService tie = service(catalog(List.of(SLOT), List.of(SLOT)),
                    new Invoice(), new SameOrderZ(), new SameOrderAsInvoice(), new Loyalty());
            assertThat(ids(tie.contributions(SLOT, TENANT)))
                    .containsExactly("crm.loyalty", "crm.a-first", "crm.z-last", "invoicing.create-invoice");
        }

        @Test
        @DisplayName("the list is immutable")
        void immutable() {
            enabled("pos", "invoicing", "crm");
            List<Extension> listed = twoContributors().contributions(SLOT, TENANT);
            assertThatThrownBy(listed::clear).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("unknown point → IllegalArgumentException, the raw value not echoed")
        void unknownPoint() {
            ExtensionRegistryService service = twoContributors();
            assertThatThrownBy(() -> service.contributions("foo.bar", TENANT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("foo.bar");
            assertThatThrownBy(() -> service.contributions(null, TENANT)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("platform-admin schema → empty, the registry is not asked")
        void platformSchema() {
            assertThat(twoContributors().contributions(SLOT, "public")).isEmpty();
            verify(registry, never()).moduleState(anyString(), anyString());
        }

        @Test
        @DisplayName("unknown tenant (core still ENABLED in the states) → empty, no state read")
        void unknownTenant() {
            when(registry.isKnownTenant(TENANT)).thenReturn(false);
            enabled("core", "pos", "invoicing", "crm");
            assertThat(twoContributors().contributions(SLOT, TENANT)).isEmpty();
            verify(registry, never()).moduleState(anyString(), anyString());
        }

        @Test
        @DisplayName("invalid schema string → IllegalArgumentException")
        void invalidSchema() {
            ExtensionRegistryService service = twoContributors();
            assertThatThrownBy(() -> service.contributions(SLOT, "x; DROP")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.contributions(SLOT, null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("declared point without contributions → empty, no state read; declaredPoints lists it")
        void declaredWithoutContributions() {
            ExtensionRegistryService service = service(catalog(List.of(), List.of()));
            assertThat(service.declaredPoints()).containsExactly(SLOT, OTHER_SLOT);
            assertThatThrownBy(() -> service.declaredPoints().add("x")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(service.contributions(SLOT, TENANT)).isEmpty();
            verify(registry, never()).moduleState(anyString(), anyString());
        }

        @Test
        @DisplayName("a contribution behind a CGLIB proxy → indexed, a snapshot of its values is returned")
        void proxied() {
            enabled("pos", "invoicing");
            ProxyFactory factory = new ProxyFactory(new Invoice());
            factory.setProxyTargetClass(true);
            Object proxy = factory.getProxy();
            ExtensionRegistryService service = service(catalog(List.of(SLOT), List.of()), proxy);
            assertThat(service.contributions(SLOT, TENANT)).singleElement()
                    .isEqualTo(new ExtensionView("invoicing", "invoicing.create-invoice", "Facturer", 20,
                            "invoicing.create-from-sale"));
        }

        @Test
        @DisplayName("the listed element is an immutable snapshot, not the bean: later changes are not seen")
        void snapshot() {
            enabled("pos", "invoicing");
            Mutable bean = new Mutable();
            ExtensionRegistryService service = service(catalog(List.of(SLOT), List.of()), bean);
            bean.label = "Autre chose";
            Extension listed = service.contributions(SLOT, TENANT).get(0);
            assertThat(listed).isNotSameAs(bean).isInstanceOf(ExtensionView.class).isNotInstanceOf(Mutable.class);
            assertThat(listed.label()).isEqualTo("Facturer");
            assertThat(((ExtensionView) listed).module()).isEqualTo("invoicing");
        }

        @Test
        @DisplayName("same extension id on two different points → allowed")
        void sameIdOtherPoint() {
            enabled("pos", "invoicing", "crm");
            ExtensionRegistryService service = service(catalog(List.of(SLOT), List.of(SLOT, OTHER_SLOT)),
                    new Invoice(), new Loyalty(), new CrmOtherSlot());
            assertThat(ids(service.contributions(OTHER_SLOT, TENANT))).containsExactly("crm.loyalty");
            assertThat(ids(service.contributions(SLOT, TENANT))).containsExactly("crm.loyalty", "invoicing.create-invoice");
        }
    }

    @Nested
    @DisplayName("boot validation")
    class BootValidation {

        @Test
        @DisplayName("manifest 'fills' entry without a contribution bean → boot fails")
        void fillWithoutBean() {
            assertThat(bootFailure(catalog(List.of(SLOT), List.of()))).contains(
                    "the manifest of module 'invoicing' lists '" + SLOT + "' in 'extensionPoints.fills' but no @ContributesExtension bean of module 'invoicing'");
        }

        @Test
        @DisplayName("bean on a point its manifest does not fill → boot fails")
        void beanWithoutFill() {
            assertThat(bootFailure(catalog(List.of(), List.of()), new Invoice())).contains(Invoice.class.getName(),
                    "module 'invoicing'", "contributes to '" + SLOT + "'", "does not list it in 'extensionPoints.fills'");
            assertThat(bootFailure(catalog(List.of(), List.of()), new NotInFills())).contains("module 'store'");
        }

        @Test
        @DisplayName("contributing module not installed → boot fails")
        void moduleNotInstalled() {
            assertThat(bootFailure(catalog(List.of(), List.of()), new GhostModule()))
                    .contains(GhostModule.class.getName(), "module 'ghost', which is not installed");
        }

        @Test
        @DisplayName("annotated bean not implementing Extension, or an instance without the annotation → boot fails")
        void notAnExtensionOrNotAnnotated() {
            assertThat(bootFailure(catalog(List.of(), List.of(SLOT)), new NotAnExtension()))
                    .contains(NotAnExtension.class.getName(), "does not implement " + Extension.class.getName())
                    .doesNotContain("contributes to it");
            assertThat(bootFailure(catalog(List.of(), List.of()), new NotAnnotated()))
                    .contains(NotAnnotated.class.getName(), "is not annotated @ContributesExtension");
        }

        @Test
        @DisplayName("duplicate extension id within a point → boot fails naming both")
        void duplicateId() {
            assertThat(bootFailure(catalog(List.of(), List.of(SLOT)), new Loyalty(), new SameIdAsLoyalty()))
                    .contains("extension id 'crm.loyalty' contributed twice to '" + SLOT + "'",
                            Loyalty.class.getName(), SameIdAsLoyalty.class.getName());
        }

        @Test
        @DisplayName("blank id, label or action → boot fails")
        void blankValues() {
            assertThat(bootFailure(catalog(List.of(), List.of(SLOT)), new Blank()))
                    .contains("has an invalid id ' '", "has a blank label", "has an invalid action ''",
                            "point '" + SLOT + "'")
                    .doesNotContain("contributes to it");
        }

        @Test
        @DisplayName("id / action not '<contributor>.<name>' or badly formed → boot fails")
        void keys() {
            assertThat(bootFailure(catalog(List.of(), List.of(SLOT)), new BadKeys()))
                    .contains(BadKeys.class.getName(), "invalid id 'invoicing.create-invoice'", "start with 'crm.'",
                            "invalid action 'crm.trailing-'");
        }

        @Test
        @DisplayName("annotation only on a superclass or an interface → boot fails naming the class")
        void inheritedAnnotation() {
            assertThat(bootFailure(catalog(List.of(SLOT), List.of()), new Invoice(), new InheritsAnnotation()))
                    .contains(InheritsAnnotation.class.getName(), "@ContributesExtension must be declared on the bean "
                            + "class itself")
                    .doesNotContain("contributed twice");
            assertThat(bootFailure(catalog(List.of(), List.of()), new ViaInterface()))
                    .contains(ViaInterface.class.getName(), "not inherited from a superclass or an interface");
        }

        @Test
        @DisplayName("a failed initialization is cached: every later call rethrows it, nothing is rebuilt")
        void failureCached() {
            ExtensionRegistryService service = new ExtensionRegistryService(registry, catalog(List.of(SLOT), List.of()),
                    null);
            Throwable first = catchThrowable(() -> service.initialize(List.of()));
            assertThat(first).isInstanceOf(IllegalStateException.class);
            assertThat(catchThrowable(() -> service.contributions(SLOT, TENANT))).isSameAs(first);
            assertThat(catchThrowable(() -> service.contributions(SLOT, TENANT))).isSameAs(first);
            assertThat(catchThrowable(() -> service.initialize(List.of())))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already initialized");
        }

        @Test
        @DisplayName("point declared by two manifests → boot fails")
        void declaredTwice() {
            ModuleCatalog twice = new ModuleCatalog(List.of(
                    TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                    TestModuleCatalogs.withExtensionPoints("pos", List.of(SLOT), List.of()),
                    TestModuleCatalogs.withExtensionPoints("store", List.of(SLOT), List.of())));
            assertThat(bootFailure(twice)).contains("extension point '" + SLOT + "' declared by two modules: 'pos'",
                    "'store'");
        }

        @Test
        @DisplayName("every problem in one message")
        void allProblemsAtOnce() {
            assertThat(bootFailure(catalog(List.of(SLOT), List.of()), new GhostModule(), new NotInFills()))
                    .contains("'ghost'", "'store'", "module 'invoicing' lists");
        }

        @Test
        @DisplayName("initialize twice → IllegalStateException")
        void initializeOnce() {
            ExtensionRegistryService service = twoContributors();
            assertThatThrownBy(() -> service.initialize(List.of())).isInstanceOf(IllegalStateException.class);
        }
    }

    // ── Story 14.5 — required permission of a contribution ──────────────────────────────────────────────

    /** pos declares SLOT; invoicing fills it and declares INVOICE_VIEW (and nothing else). */
    private static ModuleCatalog permissionCatalog() {
        com.keevo.shared.module.ModuleManifest invoicing = new com.keevo.shared.module.ModuleManifest("invoicing",
                "1.0.0", "invoicing", ModuleCategory.PREMIUM, List.of("core"), List.of(), List.of(), List.of(),
                List.of(), List.of(), new com.keevo.shared.module.ModuleManifest.ExtensionPoints(List.of(),
                List.of(SLOT)), List.of("INVOICE_VIEW"), List.of(), "module.invoicing", false);
        return new ModuleCatalog(List.of(
                TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true),
                TestModuleCatalogs.withExtensionPoints("pos", List.of(SLOT, OTHER_SLOT), List.of()),
                new com.keevo.shared.module.LoadedModuleManifest("test:invoicing", invoicing, "{}"),
                TestModuleCatalogs.withExtensionPoints("crm", List.of(), List.of())));
    }

    @Nested
    @DisplayName("required permission (Story 14.5)")
    class Permission {

        @Test
        @DisplayName("permission declared by the contributing manifest → indexed, carried by the snapshot; "
                + "contributions() does not filter on it")
        void declared() {
            enabled("pos", "invoicing");
            ExtensionRegistryService service = service(permissionCatalog(), new Guarded("INVOICE_VIEW"));
            assertThat(service.contributions(SLOT, TENANT)).singleElement()
                    .isEqualTo(new ExtensionView("invoicing", "invoicing.guarded", "Facturer", 20,
                            "invoicing.open-sale-invoice", "INVOICE_VIEW"));
        }

        @Test
        @DisplayName("no permission (default null) → allowed, snapshot permission null")
        void none() {
            enabled("pos", "invoicing");
            ExtensionRegistryService service = service(permissionCatalog(), new Guarded(null));
            assertThat(service.contributions(SLOT, TENANT).get(0).permission()).isNull();
            assertThat(new Invoice().permission()).isNull();
        }

        @Test
        @DisplayName("permission not declared by its manifest → boot fails naming class, module and permission")
        void undeclared() {
            assertThat(bootFailure(permissionCatalog(), new Guarded("INVOICE_MANAGE")))
                    .contains(Guarded.class.getName(), "module 'invoicing'", "requires permission 'INVOICE_MANAGE'",
                            "does not declare in 'permissions'")
                    .doesNotContain("contributes to it");
        }

        @Test
        @DisplayName("permission declared by another module only → boot fails")
        void otherModulesPermission() {
            assertThat(bootFailure(catalog(List.of(SLOT), List.of()), new Guarded("INVOICE_VIEW")))
                    .contains("requires permission 'INVOICE_VIEW'");
        }
    }

    // ── Story 13.9 — programmatic contributions through KeevoModule.registerExtensions ───────────────────

    static final class ContributingModule implements com.keevo.shared.module.KeevoModule {
        private final String id;
        private final java.util.function.Consumer<com.keevo.shared.module.ExtensionRegistrar> register;

        ContributingModule(String id, java.util.function.Consumer<com.keevo.shared.module.ExtensionRegistrar> register) {
            this.id = id;
            this.register = register;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void registerExtensions(com.keevo.shared.module.ExtensionRegistrar registrar) {
            register.accept(registrar);
        }
    }

    /** Not annotated: contributed programmatically. */
    static final class PlainInvoice extends Action {
        PlainInvoice() {
            super("invoicing.create-invoice", "Facturer", 20, "invoicing.create-from-sale");
        }
    }

    @Nested
    @DisplayName("programmatic contributions (Story 13.9)")
    class Programmatic {

        @Test
        @DisplayName("contributed by the module → listed while declarer and contributor are ENABLED")
        void listed() {
            ExtensionRegistryService service = service(catalog(List.of(SLOT), List.of(SLOT)), new Loyalty(),
                    new ContributingModule("invoicing", r -> r.contribute(SLOT, new PlainInvoice())));
            enabled("pos", "invoicing", "crm");
            assertThat(ids(service.contributions(SLOT, TENANT)))
                    .containsExactly("crm.loyalty", "invoicing.create-invoice");
            states.put("invoicing", ModuleState.DISABLED);
            assertThat(ids(service.contributions(SLOT, TENANT))).containsExactly("crm.loyalty");
        }

        @Test
        @DisplayName("same validation as the annotation: point not in 'fills', duplicate id, missing contribution")
        void validated() {
            assertThat(bootFailure(catalog(List.of(), List.of()),
                    new ContributingModule("invoicing", r -> r.contribute(SLOT, new PlainInvoice()))))
                    .contains("does not list it in 'extensionPoints.fills'");
            assertThat(bootFailure(catalog(List.of(SLOT), List.of()), new Invoice(),
                    new ContributingModule("invoicing", r -> r.contribute(SLOT, new PlainInvoice()))))
                    .contains("contributed twice");
            assertThat(bootFailure(catalog(List.of(SLOT), List.of()), new ContributingModule("invoicing", r -> { })))
                    .contains("lists '" + SLOT + "' in 'extensionPoints.fills'");
            assertThat(bootFailure(catalog(List.of(SLOT), List.of()),
                    new ContributingModule("invoicing", r -> r.contribute(" ", new PlainInvoice()))))
                    .contains("blank extension point");
        }
    }
}
