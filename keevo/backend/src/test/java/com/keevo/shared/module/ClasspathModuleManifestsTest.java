package com.keevo.shared.module;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.1 — the manifests shipped in {@code src/main/resources/keevo/modules} load and carry the decided graph:
 * the 10 base modules (free) + the 7 paid feature modules of plan §6.1 (Story 13.10a).
 */
class ClasspathModuleManifestsTest {

    @Test
    void theShippedManifestsAreValid() {
        Map<String, ModuleManifest> byId = ModuleManifestLoader.loadFromClasspath(new PathMatchingResourcePatternResolver()).stream()
                .map(LoadedModuleManifest::manifest)
                .collect(Collectors.toMap(ModuleManifest::id, Function.identity()));

        List<String> paid = List.of("multi-store", "counting", "advanced-reports", "report-config", "alerts",
                "profitability", "exports", "invoicing");
        assertThat(byId).containsOnlyKeys("core", "catalog", "crm", "inventory", "store", "pos", "reporting",
                "messaging", "sync", "admin", "multi-store", "counting", "advanced-reports", "report-config", "alerts",
                "profitability", "exports", "invoicing");
        assertThat(byId.values()).allSatisfy(m -> {
            // 13.6: crm provides; 13.7: pos declares an extension point
            assertThat(m.version()).isEqualTo(m.id().equals("crm") || m.id().equals("pos") ? "1.1.0" : "1.0.0");
            assertThat(m.free()).isEqualTo(!paid.contains(m.id()));
            assertThat(m.entitlement()).isEqualTo("module." + m.id());
        });
        // 13.10a: paid feature modules — category premium, hard dependencies of plan §6.1 decisions
        paid.forEach(id -> assertThat(byId.get(id).category()).isEqualTo(ModuleCategory.PREMIUM));
        assertThat(byId.get("multi-store").requires()).containsExactly("core", "store", "inventory");
        assertThat(byId.get("counting").requires()).containsExactly("core", "inventory");
        assertThat(byId.get("alerts").requires()).containsExactly("core", "messaging");
        for (String id : List.of("advanced-reports", "report-config", "profitability", "exports")) {
            assertThat(byId.get(id).requires()).containsExactly("core", "reporting");
        }
        assertThat(byId.get("core").category()).isEqualTo(ModuleCategory.CORE);
        assertThat(byId.get("core").requires()).isEmpty();
        assertThat(byId.get("sync").category()).isEqualTo(ModuleCategory.CORE);
        assertThat(byId.get("admin").category()).isEqualTo(ModuleCategory.PLATFORM);
        assertThat(byId.get("pos").requires()).containsExactly("core", "catalog", "inventory");
        assertThat(byId.get("pos").enhances()).containsExactly("crm", "reporting");
        assertThat(byId.get("inventory").requires()).containsExactly("core", "catalog");
        assertThat(byId.get("reporting").enhances()).containsExactly("pos", "inventory", "catalog");
        assertThat(byId.get("messaging").enhances()).containsExactly("reporting", "pos", "inventory");
        assertThat(byId.get("catalog").enhances()).containsExactly("reporting");
        assertThat(byId.get("crm").enhances()).containsExactly("pos");
        assertThat(byId.get("crm").provides()).containsExactly("ClientLookupPort");
        // 14.1: invoicing — standalone (core only), enhances pos/crm, optional ClientLookupPort, own permissions
        assertThat(byId.get("invoicing").requires()).containsExactly("core");
        assertThat(byId.get("invoicing").enhances()).containsExactly("pos", "crm");
        assertThat(byId.get("invoicing").needs()).containsExactly("ClientLookupPort?");
        assertThat(byId.get("invoicing").permissions()).containsExactly("INVOICE_CREATE", "INVOICE_VIEW",
                "INVOICE_SEND", "INVOICE_MANAGE");
        assertThat(byId.get("invoicing").publishes()).containsExactly("invoice.created", "invoice.issued",
                "invoice.paid", "invoice.cancelled", "invoice.sent");
        assertThat(byId.get("invoicing").displayName()).isEqualTo("Facturation");
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("invoicing"))
                .allSatisfy(m -> assertThat(m.permissions()).isEmpty());
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("crm"))
                .allSatisfy(m -> assertThat(m.provides()).isEmpty());
        assertThat(byId.get("pos").extensionPoints().declares()).containsExactly("pos.checkout.post-sale-actions");
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("pos"))
                .allSatisfy(m -> assertThat(m.extensionPoints().declares()).isEmpty());
        assertThat(byId.values()).allSatisfy(m -> assertThat(m.extensionPoints().fills()).isEmpty());
        assertThat(byId.values()).filteredOn(m -> !m.isCore())
                .allSatisfy(m -> assertThat(m.requires()).contains("core"));
        assertThat(byId.values().stream().filter(m -> m.category() == ModuleCategory.BUSINESS).map(ModuleManifest::id)
                .toList()).hasSize(7);
        assertThat(List.copyOf(byId.values())).allSatisfy(m -> assertThat(m.displayName()).isNotBlank());
    }
}
