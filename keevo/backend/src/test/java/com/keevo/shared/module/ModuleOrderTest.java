package com.keevo.shared.module;

import com.keevo.shared.module.application.ModuleCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 16.12 — the table-creation order of the shipped modules (registration and provisioning), derived from the
 * manifests by {@link ModuleOrder}: pinned here, and checked against the cross-module foreign keys of the foundation
 * scripts.
 */
@DisplayName("ModuleOrder (Story 16.12)")
class ModuleOrderTest {

    private static final ModuleCatalog CATALOG = new ModuleCatalog(new PathMatchingResourcePatternResolver());

    private static List<String> ids(List<ModuleManifest> modules) {
        return modules.stream().map(ModuleManifest::id).toList();
    }

    @Test
    @DisplayName("foundation order of the shipped manifests is pinned; paid and platform modules come after")
    void pinnedOrder() {
        // 21.7: client-portal is free but owns no tenant table (its data is public): it ranks among the foundation
        // modules and creates nothing, so the pinned order is that of the modules with tables
        List<String> order = ids(ModuleOrder.order(CATALOG.manifests())).stream()
                .filter(id -> !id.equals("client-portal")).toList();

        assertThat(order.subList(0, 9)).containsExactly("core", "reporting", "catalog", "crm", "messaging", "store",
                "sync", "inventory", "pos");
        assertThat(order.subList(9, order.size())).doesNotContainAnyElementsOf(order.subList(0, 9))
                .contains("invoicing", "admin");
        assertThat(FoundationScripts.fromClasspath().moduleIds()).containsExactlyElementsOf(order.subList(0, 9));
    }

    @Test
    @DisplayName("every cross-module foreign key of the foundation scripts points to a module placed before")
    void foreignKeysPointBackwards() {
        List<String> order = ids(ModuleOrder.order(CATALOG.manifests()));
        // referencing module → referenced module (sales → clients, product_suppliers → products,
        // inventory_sessions → stores, stock_* → products)
        List<List<String>> references = List.of(List.of("pos", "crm"), List.of("pos", "inventory"),
                List.of("crm", "catalog"), List.of("inventory", "store"), List.of("inventory", "catalog"));
        for (List<String> ref : references) {
            assertThat(order.indexOf(ref.get(1))).as(ref.get(0) + " after " + ref.get(1))
                    .isLessThan(order.indexOf(ref.get(0)));
        }
    }

    @Test
    @DisplayName("requires first, then foundation before paid, shallower depth, enhanced peer, id")
    void rule() {
        ModuleManifest core = TestModuleCatalogs.loaded("core", ModuleCategory.CORE, true).manifest();
        ModuleManifest b = manifest("bbb", true, List.of("core"), List.of());
        ModuleManifest a = manifest("aaa", true, List.of("core", "bbb"), List.of());
        ModuleManifest z = manifest("zzz", true, List.of("core"), List.of());
        ModuleManifest y = manifest("yyy", true, List.of("core"), List.of("zzz")); // zzz enhanced by a peer
        ModuleManifest paid = manifest("ccc", false, List.of("core"), List.of());

        assertThat(ids(ModuleOrder.order(List.of(a, paid, y, z, b, core))))
                .containsExactly("core", "zzz", "bbb", "yyy", "aaa", "ccc");
    }

    @Test
    @DisplayName("a requires cycle is refused")
    void cycle() {
        ModuleManifest a = manifest("aaa", true, List.of("bbb"), List.of());
        ModuleManifest b = manifest("bbb", true, List.of("aaa"), List.of());
        assertThatThrownBy(() -> ModuleOrder.order(List.of(a, b))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ModuleManifest manifest(String id, boolean free, List<String> requires, List<String> enhances) {
        ModuleManifest base = TestModuleCatalogs.loaded(id, free ? ModuleCategory.BUSINESS : ModuleCategory.PREMIUM,
                free).manifest();
        return new ModuleManifest(base.id(), base.version(), base.displayName(), base.category(), requires, enhances,
                base.publishes(), base.consumes(), base.provides(), base.needs(), base.extensionPoints(),
                base.permissions(), base.routes(), base.entitlement(), base.free());
    }
}
