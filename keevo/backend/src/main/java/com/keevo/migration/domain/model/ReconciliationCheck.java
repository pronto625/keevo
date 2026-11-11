package com.keevo.migration.domain.model;

import java.util.List;

/**
 * Story 24.8 — one control of analysis §10.3 with its lines; its status is the worst of its lines (GAP, then
 * EXPLAINED, else OK).
 *
 * @param code           stable key of the control ({@link Kind} name), for the dashboard
 * @param toleranceLabel the tolerance in French
 */
public record ReconciliationCheck(String code, String label, String toleranceLabel, ReconciliationLine.Status status,
                                  String statusLabel, List<ReconciliationLine> lines) {

    /** The controls of §10.3, in report order. */
    public enum Kind {
        COUNTS("Nombre de lignes par table", "0 (regroupements et exclusions expliqués)"),
        SALES_BY_STORE_MONTH("Ventes non annulées par boutique et par mois", "±1 F par document"),
        COLLECTED("Montant encaissé (acomptes et échéances payées)", "±1 F par montant arrondi"),
        EXPENSES_BY_MONTH("Dépenses par mois", "0"),
        OUTSTANDING("Restant dû des ventes en cours", "±1 F par montant arrondi"),
        SAMPLE("Échantillon de documents comparés champ à champ", "0"),
        SUBSCRIPTION("Abonnement et crédit du compte", "0"),
        PUBLIC_LINKS("Anciens liens publics", "0"),
        NOTED_ANOMALIES("Anomalies notées à l'import (valeurs Bref conservées)", "sans objet");

        private final String label;
        private final String tolerance;

        Kind(String label, String tolerance) {
            this.label = label;
            this.tolerance = tolerance;
        }

        public String label() {
            return label;
        }

        public String tolerance() {
            return tolerance;
        }
    }

    public ReconciliationCheck {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static ReconciliationCheck of(Kind kind, List<ReconciliationLine> lines) {
        ReconciliationLine.Status worst = ReconciliationLine.Status.OK;
        for (ReconciliationLine l : lines) {
            if (l.status().ordinal() > worst.ordinal()) {
                worst = l.status();
            }
        }
        return new ReconciliationCheck(kind.name(), kind.label(), kind.tolerance(), worst, worst.label(), lines);
    }
}
