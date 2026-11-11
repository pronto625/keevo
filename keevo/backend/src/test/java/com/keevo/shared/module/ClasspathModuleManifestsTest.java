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
                "profitability", "exports", "invoicing", "expenses", "finance", "ai-assistant", "mcp-access",
                "whatsapp-cloud");
        assertThat(byId).containsOnlyKeys("core", "catalog", "crm", "inventory", "store", "pos", "reporting",
                "messaging", "sync", "admin", "client-portal", "multi-store", "counting", "advanced-reports", "report-config", "alerts",
                "profitability", "exports", "invoicing", "expenses", "finance", "ai-assistant", "mcp-access",
                "whatsapp-cloud");
        assertThat(byId.values()).allSatisfy(m -> {
            // 13.6: crm provides; 13.7: pos declares an extension point; 14.3: crm search, pos needs ClientLookupPort?
            // 14.4: pos publishes sale.completed (1.3.0), invoicing consumes it (1.1.0)
            // 14.5: invoicing fills the POS checkout slot (1.2.0)
            // 14.7: invoicing publishes credit-note.issued and consumes sale.cancelled / sale.corrected (1.3.0),
            //       pos publishes them (1.4.0)
            // 15.2: routes declared for crm (1.3.0), multi-store, counting, profitability, exports, report-config (1.1.0)
            // 15.5: invoicing declares its new screens' routes (1.4.0)
            // 15.8: advanced-reports declares the « Équipe » leaderboard route (1.1.0)
            String expected = switch (m.id()) {
                case "crm" -> "1.3.0";
                case "multi-store", "counting", "profitability", "exports", "report-config", "advanced-reports" -> "1.1.0";
                case "pos" -> "1.4.0";
                case "invoicing" -> "1.4.0";
                default -> "1.0.0";
            };
            assertThat(m.version()).isEqualTo(expected);
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
        assertThat(byId.get("pos").needs()).containsExactly("ClientLookupPort?");
        // 14.1: invoicing — standalone (core only), enhances pos/crm, optional ClientLookupPort, own permissions
        assertThat(byId.get("invoicing").requires()).containsExactly("core");
        // 20.6: enhances catalog too, optional ProductLookupPort (document → catalogue products)
        assertThat(byId.get("invoicing").enhances()).containsExactly("pos", "crm", "catalog");
        assertThat(byId.get("invoicing").needs()).containsExactly("ClientLookupPort?", "ProductLookupPort?");
        assertThat(byId.get("catalog").provides()).containsExactly("ProductLookupPort");
        assertThat(byId.get("invoicing").permissions()).containsExactly("INVOICE_CREATE", "INVOICE_VIEW",
                "INVOICE_SEND", "INVOICE_MANAGE");
        assertThat(byId.get("invoicing").publishes()).containsExactly("invoice.created", "invoice.issued",
                "invoice.paid", "invoice.cancelled", "invoice.sent", "credit-note.issued");
        assertThat(byId.get("invoicing").displayName()).isEqualTo("Facturation");
        // 14.4: the POS → Invoicing bridge — pos publishes sale.completed, invoicing consumes it
        assertThat(byId.get("pos").publishes()).containsExactly("sale.completed", "sale.cancelled",
                "sale.corrected");
        assertThat(byId.get("invoicing").consumes()).containsExactly("sale.completed", "sale.cancelled",
                "sale.corrected");
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("invoicing"))
                .allSatisfy(m -> assertThat(m.consumes()).isEmpty());
        // 20.10: expenses — premium, core + store, its own permissions, no routes (RN app in the Bref repo)
        assertThat(byId.get("expenses").permissions()).containsExactly("EXPENSE_VIEW", "EXPENSE_CREATE",
                "EXPENSE_DELETE");
        assertThat(byId.get("expenses").requires()).containsExactly("core", "store");
        assertThat(byId.get("expenses").category()).isEqualTo(ModuleCategory.PREMIUM);
        assertThat(byId.get("expenses").displayName()).isEqualTo("Dépenses");
        assertThat(byId.get("expenses").routes()).isEmpty();
        // 20.11: finance — premium, requires expenses and reporting, FINANCE_VIEW held by the owner only, no routes
        assertThat(byId.get("finance").permissions()).containsExactly("FINANCE_VIEW");
        assertThat(byId.get("finance").requires()).containsExactly("core", "store", "expenses", "reporting");
        assertThat(byId.get("finance").category()).isEqualTo(ModuleCategory.PREMIUM);
        assertThat(byId.get("finance").displayName()).isEqualTo("Finance");
        assertThat(byId.get("finance").routes()).isEmpty();
        assertThat(byId.get("finance").free()).isFalse();
        // 21.4: ai-assistant — premium (paid) (granted to every plan by V65), requires core only, no permission
        assertThat(byId.get("ai-assistant").requires()).containsExactly("core");
        assertThat(byId.get("ai-assistant").category()).isEqualTo(ModuleCategory.PREMIUM);
        assertThat(byId.get("ai-assistant").displayName()).isEqualTo("Assistant IA");
        assertThat(byId.get("ai-assistant").free()).isFalse();
        // 21.9: mcp-access — premium (paid) (TEAM, BUSINESS, BUSINESS_TRIAL, PREMIUM, PREMIUM_TRIAL by V66), core only
        assertThat(byId.get("mcp-access").requires()).containsExactly("core");
        assertThat(byId.get("mcp-access").category()).isEqualTo(ModuleCategory.PREMIUM);
        assertThat(byId.get("mcp-access").displayName()).isEqualTo("Accès IA externe (MCP)");
        assertThat(byId.get("mcp-access").free()).isFalse();
        // 21.5: whatsapp-cloud — premium (paid) (BUSINESS, BUSINESS_TRIAL, PREMIUM, PREMIUM_TRIAL by V68), core only
        assertThat(byId.get("whatsapp-cloud").requires()).containsExactly("core");
        assertThat(byId.get("whatsapp-cloud").category()).isEqualTo(ModuleCategory.PREMIUM);
        assertThat(byId.get("whatsapp-cloud").displayName()).isEqualTo("WhatsApp Business");
        assertThat(byId.get("whatsapp-cloud").free()).isFalse();
        // 21.7: client-portal — free business module (the merchant's switch is « Mes modules »), core only, no route
        assertThat(byId.get("client-portal").requires()).containsExactly("core");
        assertThat(byId.get("client-portal").category()).isEqualTo(ModuleCategory.BUSINESS);
        assertThat(byId.get("client-portal").displayName()).isEqualTo("Portail client");
        assertThat(byId.get("client-portal").free()).isTrue();
        assertThat(byId.get("client-portal").ownerCanToggle()).isTrue();
        // 16.13 reprise (2026-11-10): catalog — purchase prices for OWNER and MANAGER only
        // 19.5b (2026-11-11): + PRODUCT_MERGE for OWNER and MANAGER
        assertThat(byId.get("catalog").permissions()).containsExactly("PURCHASE_PRICE_VIEW", "PRODUCT_MERGE");
        assertThat(byId.get("catalog").rolePermissions().get("MANAGER"))
                .containsExactly("PURCHASE_PRICE_VIEW", "PRODUCT_MERGE");
        assertThat(byId.get("catalog").rolePermissions().get("EMPLOYEE")).isEmpty();
        assertThat(byId.get("catalog").rolePermissions().get("VIEWER")).isEmpty();
        // 19.5b — fine rights of the store manager (and the viewer's store sales)
        assertThat(byId.get("pos").permissions()).containsExactly("STORE_SALES_VIEW");
        assertThat(byId.get("pos").rolePermissions().get("MANAGER")).containsExactly("STORE_SALES_VIEW");
        assertThat(byId.get("pos").rolePermissions().get("VIEWER")).containsExactly("STORE_SALES_VIEW");
        assertThat(byId.get("pos").rolePermissions().get("EMPLOYEE")).isEmpty();
        assertThat(byId.get("counting").permissions()).containsExactly("INVENTORY_SESSION_MANAGE");
        assertThat(byId.get("counting").rolePermissions().get("MANAGER")).containsExactly("INVENTORY_SESSION_MANAGE");
        assertThat(byId.get("core").permissions()).containsExactly("TEAM_MANAGE");
        assertThat(byId.get("core").rolePermissions().get("MANAGER")).containsExactly("TEAM_MANAGE");
        assertThat(byId.values()).filteredOn(m -> !List.of("invoicing", "expenses", "finance", "catalog", "pos",
                        "counting", "core").contains(m.id()))
                .allSatisfy(m -> assertThat(m.permissions()).isEmpty());
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("crm") && !m.id().equals("catalog"))
                .allSatisfy(m -> assertThat(m.provides()).isEmpty());
        assertThat(byId.get("pos").extensionPoints().declares()).containsExactly("pos.checkout.post-sale-actions");
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("pos"))
                .allSatisfy(m -> assertThat(m.extensionPoints().declares()).isEmpty());
        // 14.5: invoicing contributes « Facturer » to the POS checkout slot
        assertThat(byId.get("invoicing").extensionPoints().fills()).containsExactly("pos.checkout.post-sale-actions");
        assertThat(byId.values()).filteredOn(m -> !m.id().equals("invoicing"))
                .allSatisfy(m -> assertThat(m.extensionPoints().fills()).isEmpty());
        assertThat(byId.values()).filteredOn(m -> !m.isCore())
                .allSatisfy(m -> assertThat(m.requires()).contains("core"));
        assertThat(byId.values().stream().filter(m -> m.category() == ModuleCategory.BUSINESS).map(ModuleManifest::id)
                .toList()).hasSize(8); // 21.7: + client-portal
        assertThat(List.copyOf(byId.values())).allSatisfy(m -> assertThat(m.displayName()).isNotBlank());
        // 15.2: route -> module mapping (app guard); the free CSV export screens stay unmapped
        assertThat(byId.get("exports").routes()).containsExactly("/exports", "/exports/:id");
        assertThat(byId.get("crm").routes()).contains("/clients", "/suppliers/:id");
        assertThat(byId.values()).filteredOn(m -> m.free() && !m.id().equals("crm"))
                .allSatisfy(m -> assertThat(m.routes()).isEmpty());
    }
}
