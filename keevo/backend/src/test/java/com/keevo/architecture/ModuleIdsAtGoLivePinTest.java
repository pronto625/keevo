package com.keevo.architecture;

import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.13 — the modules shipped when V50 backfilled the owner activation (go-live of the interface profile). A
 * module added afterwards is new to every existing user and must stay invisible until their owner enables it: it ships
 * a public migration owner-disabling it for the existing tenants ({@code db/modules/README.md}, « Modules introduced
 * after the go-live »), then its id is added to {@link #KNOWN} (with the migration's version in the comment).
 */
@DisplayName("19.13 — module ids pinned at the owner-activation go-live (V50)")
class ModuleIdsAtGoLivePinTest {

    /** The 18 module ids of {@code keevo/modules/*}/module.json at V50. */
    static final Set<String> KNOWN = Set.of("admin", "advanced-reports", "alerts", "catalog", "core", "counting",
            "crm", "exports", "inventory", "invoicing", "messaging", "multi-store", "pos", "profitability",
            "report-config", "reporting", "store", "sync",
            "expenses", // 20.10 — owner-disabled for existing tenants by V56
            "finance", // 20.11 — owner-disabled for existing tenants by V58
            "ai-assistant", // 21.4 — owner-disabled for existing tenants by V65
            "mcp-access", // 21.9 — owner-disabled for existing tenants by V66
            "whatsapp-cloud", // 21.5 — owner-disabled for existing tenants by V68
            "client-portal", // 21.7 — owner-disabled for existing tenants by V70, re-enabled by V84 (like Bref)
            "migration"); // 24.2 — platform module (Bref migration tool), owner-disabled for existing tenants by V81

    @Test
    @DisplayName("every shipped module id is pinned (a new module ships its owner-disabling migration first)")
    void shippedModulesArePinned() {
        Set<String> shipped = ModuleManifestLoader.loadFromClasspath(new PathMatchingResourcePatternResolver())
                .stream().map(LoadedModuleManifest::manifest).map(m -> m.id())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> added = new TreeSet<>(shipped);
        added.removeAll(KNOWN);
        assertThat(added).as("""
                new module(s) since the V50 go-live: ship a public migration (db/migration) owner-disabling each of \
                them for the existing tenants — INSERT INTO public.tenant_module_activation … SELECT t.id, '<id>', \
                false … ON CONFLICT DO NOTHING (rule in src/main/resources/db/modules/README.md, « Modules \
                introduced after the go-live ») — then add the id to ModuleIdsAtGoLivePinTest.KNOWN""").isEmpty();
        Set<String> removed = new TreeSet<>(KNOWN);
        removed.removeAll(shipped);
        assertThat(removed).as("pinned module ids no longer shipped: remove them from KNOWN").isEmpty();
    }
}
