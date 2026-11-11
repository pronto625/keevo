package com.keevo.whatsappcloud.campaign.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Purchase criteria of a campaign (21.11): validation, merge, French labels")
class PurchaseCriteriaTest {

    private static PurchaseCriteria of(Integer inactive, Integer within, Long min, Long max, Integer cMin, Integer cMax) {
        return new PurchaseCriteria(inactive, within, min, max, cMin, cMax);
    }

    @Test
    void none_isEmpty_andValid() {
        assertThat(PurchaseCriteria.NONE.isEmpty()).isTrue();
        assertThat(PurchaseCriteria.NONE.problem()).isNull();
        assertThat(PurchaseCriteria.NONE.labels()).isEmpty();
        assertThat(of(30, null, null, null, null, null).isEmpty()).isFalse();
    }

    @Test
    void problem_rangesAndContradictions() {
        assertThat(of(0, null, null, null, null, null).problem().field()).isEqualTo("inactiveForDays");
        assertThat(of(3651, null, null, null, null, null).problem()).isNotNull();
        assertThat(of(3650, 3650, null, null, null, null).problem().field()).as("within ≤ inactive")
                .isEqualTo("purchasedWithinDays");
        assertThat(of(30, 60, null, null, null, null).problem()).isNull();
        assertThat(of(null, null, -1L, null, null, null).problem().field()).isEqualTo("totalSpentMin");
        assertThat(of(null, null, null, PurchaseCriteria.MAX_AMOUNT + 1, null, null).problem().field())
                .isEqualTo("totalSpentMax");
        assertThat(of(null, null, 5_000L, 1_000L, null, null).problem().message())
                .isEqualTo("Le montant minimum des achats ne peut pas dépasser le maximum");
        assertThat(of(null, null, 1_000L, 1_000L, null, null).problem()).isNull();
        assertThat(of(null, null, null, null, 3, 1).problem().field()).isEqualTo("totalInvoicesMin");
        assertThat(of(null, null, null, null, null, -1).problem().field()).isEqualTo("totalInvoicesMax");
        assertThat(of(null, null, null, null, 0, 0).problem()).isNull();
    }

    @Test
    void merge_presentFieldsOnly_presentNullClears() {
        PurchaseCriteria current = of(30, null, 5_000L, null, null, 4);
        PurchaseCriteria merged = current.merge(of(null, 90, 1L, 2L, 3, null),
                Set.of("inactiveForDays", "purchasedWithinDays", "totalInvoicesMax"));
        assertThat(merged).isEqualTo(of(null, 90, 5_000L, null, null, null));
        assertThat(current.merge(null, Set.of())).isEqualTo(current);
    }

    @Test
    void labels_french() {
        assertThat(of(30, null, null, null, null, null).labels()).containsExactly("Sans achat depuis 30 jours");
        assertThat(of(1, null, null, null, null, null).labels()).containsExactly("Sans achat depuis 1 jour");
        assertThat(of(null, 1, null, null, null, null).labels()).containsExactly("Achat au cours du dernier jour");
        assertThat(of(null, 90, null, null, null, null).labels())
                .containsExactly("Achat au cours des 90 derniers jours");
        assertThat(of(null, null, 10_000L, 1_500_000L, null, null).labels())
                .containsExactly("Total des achats entre 10 000 et 1 500 000 FCFA");
        assertThat(of(null, null, 10_000L, null, null, null).labels())
                .containsExactly("Total des achats d'au moins 10 000 FCFA");
        assertThat(of(null, null, null, 500L, null, null).labels()).containsExactly("Total des achats de 500 FCFA au plus");
        assertThat(of(null, null, null, null, 2, 5).labels()).containsExactly("Entre 2 et 5 achats");
        assertThat(of(null, null, null, null, 1, 1).labels()).containsExactly("Exactement 1 achat");
        assertThat(of(null, null, null, null, 1, null).labels()).containsExactly("Au moins 1 achat");
        assertThat(of(null, null, null, null, null, 0).labels()).containsExactly("Aucun achat");
        assertThat(of(null, null, null, null, null, 3).labels()).containsExactly("3 achats au plus");
        assertThat(of(60, 90, 1L, null, 2, null).labels()).hasSize(4);
    }

    @Test
    void amount_groupsThousands() {
        assertThat(PurchaseCriteria.amount(0)).isEqualTo("0");
        assertThat(PurchaseCriteria.amount(999)).isEqualTo("999");
        assertThat(PurchaseCriteria.amount(1_000)).isEqualTo("1 000");
        assertThat(PurchaseCriteria.amount(1_000_000_000_000L)).isEqualTo("1 000 000 000 000");
    }
}
