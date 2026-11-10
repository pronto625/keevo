package com.keevo.mcp.token.application.service;

import com.keevo.shared.ai.AiToolDescriptor;

import java.util.Map;

/**
 * Story 21.9 — the short French label of each AI tool, shown to the owner when choosing what an external AI client
 * may read (the tool name is an API handle, never displayed). A tool without a label here falls back to the first
 * clause of its French description ({@code McpServerFullContextTest#everyToolHasALabel} requires a label for every tool of the registry).
 */
public final class McpToolLabels {

    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("activity_overview", "Tableau de bord de l'activité"),
            Map.entry("compare_revenue_periods", "Comparaison du chiffre d'affaires entre deux périodes"),
            Map.entry("financial_result", "Résultat financier"),
            Map.entry("invoice_collections", "Encaissements des factures"),
            Map.entry("list_expenses", "Dépenses"),
            Map.entry("list_stores", "Boutiques"),
            Map.entry("low_stock_products", "Produits en stock bas"),
            Map.entry("revenue_summary", "Chiffre d'affaires et marge"),
            Map.entry("sales_history", "Historique des ventes"),
            Map.entry("search_clients", "Clients"),
            Map.entry("search_products", "Produits"),
            Map.entry("seller_ranking", "Classement des vendeurs"),
            Map.entry("stock_by_store", "Stock par boutique"),
            Map.entry("store_performance", "Performance des boutiques"),
            Map.entry("unpaid_call_list", "Clients à relancer"),
            Map.entry("unpaid_invoices", "Factures impayées"));

    private McpToolLabels() {
    }

    /** Whether the tool has its own label (coverage: every registry tool must). */
    public static boolean hasLabel(String toolName) {
        return LABELS.containsKey(toolName);
    }

    public static String label(AiToolDescriptor tool) {
        String label = LABELS.get(tool.name());
        if (label != null) {
            return label;
        }
        String description = tool.description() == null ? "" : tool.description().strip();
        int cut = description.length();
        for (char stop : new char[] {':', '.', '('}) {
            int at = description.indexOf(stop);
            if (at > 0) {
                cut = Math.min(cut, at);
            }
        }
        String clause = description.substring(0, Math.min(cut, 60)).strip();
        return clause.isEmpty() ? "Donnée Keevo" : clause;
    }
}
