package com.keevo.inventory.counting.adapter.out.export;

import java.util.Map;

/** French labels of inventory codes in exports (Story v1s-18-2). */
final class InventoryLabels {

    private InventoryLabels() {
    }

    static final Map<String, String> SCOPE = Map.of("FULL", "Complet", "PARTIAL", "Partiel");

    static final Map<String, String> STATUS = Map.of(
            "IN_PROGRESS", "En cours",
            "VALIDATED", "Validé",
            "CANCELLED", "Annulé");
}
