package com.keevo.shared.module.application;

import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleManifest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Story 19.14 — the catalogue of permissions an owner may tick when he defines a role: the permissions declared by
 * the manifests of the modules his company can use ({@link EntitlementService#enabledModules}), grouped by module
 * (manifest display name), each with its French label — the screen never shows a permission code.
 *
 * <p>A permission is <b>grantable</b> when it is declared by an installed manifest and has a label here. A declared
 * permission without a label is never offered (a test pins that every shipped permission has one). Reprise 11/11/2026
 * (user decision, DD4 reversed): « Voir la situation financière » is grantable too — the finance summary of a member
 * is computed on their own store only (20.11 {@code FinanceAccess}), never the company's other stores.
 */
@Service
public class PermissionCatalog {

    /** French labels of the shipped permissions (« Voir les prix d'achat »…). */
    static final Map<String, String> LABELS = Map.ofEntries(
            entry("SALE_CREATE", "Créer des ventes"),
            entry("STORE_SALES_VIEW", "Voir les ventes de toute la boutique"),
            entry("PURCHASE_PRICE_VIEW", "Voir les prix d'achat"),
            entry("PRODUCT_MERGE", "Fusionner des produits"),
            entry("INVENTORY_SESSION_MANAGE", "Gérer les inventaires de la boutique"),
            entry("TEAM_MANAGE", "Gérer l'équipe de sa boutique"),
            entry("EXPENSE_VIEW", "Voir les dépenses"),
            entry("EXPENSE_CREATE", "Saisir et modifier des dépenses"),
            entry("EXPENSE_DELETE", "Supprimer des dépenses"),
            entry("INVOICE_VIEW", "Voir les devis et factures"),
            entry("INVOICE_CREATE", "Créer des devis et factures"),
            entry("INVOICE_SEND", "Envoyer des devis et factures"),
            entry("INVOICE_MANAGE", "Gérer les factures (annulation, avoirs, réglages)"),
            entry("DOCUMENT_STORE_VIEW", "Voir les documents de toute la boutique"),
            entry("FINANCE_VIEW", "Voir la situation financière"));

    /**
     * One permission of the catalogue.
     *
     * @param code     the value the app sends back (never shown)
     * @param label    French label shown to the owner
     * @param readOnly a read permission ({@value ModulePermissions#READ_SUFFIX}): the only kind a « Lecteur » role holds
     */
    public record Entry(String code, String label, boolean readOnly) {
    }

    /** The grantable permissions of one module, under its display name. */
    public record Group(String module, String moduleLabel, List<Entry> permissions) {
    }

    private final ModulePermissions permissions;
    private final ModuleCatalog catalog;
    private final EntitlementService entitlements;

    public PermissionCatalog(ModulePermissions permissions, ModuleCatalog catalog, EntitlementService entitlements) {
        this.permissions = permissions;
        this.catalog = catalog;
        this.entitlements = entitlements;
    }

    /** The French label of {@code permission}, or {@code null} when it has none. */
    public static String label(String permission) {
        return permission == null ? null : LABELS.get(permission);
    }

    /** Whether an owner-defined role may carry {@code permission} at all (declared and labelled). */
    public boolean isGrantable(String permission) {
        return permission != null && permissions.isDeclared(permission) && LABELS.containsKey(permission);
    }

    /** As {@link #isGrantable(String)}, and its module is usable by the company {@code tenantSchema}. */
    public boolean isGrantable(String tenantSchema, String permission) {
        return isGrantable(entitlements.enabledModules(tenantSchema), permission);
    }

    /** As {@link #isGrantable(String)}, and its module is among {@code enabledModules} (read once by the caller). */
    public boolean isGrantable(Set<String> enabledModules, String permission) {
        return isGrantable(permission) && enabledModules.contains(permissions.moduleOf(permission));
    }

    /** The modules the company can use (one entitlement read). */
    public Set<String> enabledModules(String tenantSchema) {
        return entitlements.enabledModules(tenantSchema);
    }

    /**
     * The grantable permissions of the company's usable modules, grouped by module (modules by display name, then
     * permissions by label). Cost: one entitlement read, O(declared permissions).
     */
    public List<Group> grantable(String tenantSchema) {
        Set<String> enabled = entitlements.enabledModules(tenantSchema);
        Map<String, List<Entry>> byModule = new LinkedHashMap<>();
        for (String code : permissions.declared()) {
            String module = permissions.moduleOf(code);
            if (!isGrantable(code) || module == null || !enabled.contains(module)) {
                continue;
            }
            byModule.computeIfAbsent(module, m -> new ArrayList<>())
                    .add(new Entry(code, LABELS.get(code), code.endsWith(ModulePermissions.READ_SUFFIX)));
        }
        List<Group> groups = new ArrayList<>();
        byModule.forEach((module, entries) -> {
            entries.sort(Comparator.comparing(Entry::label));
            String moduleLabel = catalog.find(module).map(ModuleManifest::displayName).orElse(module);
            groups.add(new Group(module, moduleLabel, List.copyOf(entries)));
        });
        groups.sort(Comparator.comparing(Group::moduleLabel));
        return List.copyOf(groups);
    }
}
