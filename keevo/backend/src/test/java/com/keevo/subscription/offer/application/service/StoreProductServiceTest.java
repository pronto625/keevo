package com.keevo.subscription.offer.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StoreProductService — gap between the Apple price and the grid (Story 21.2)")
class StoreProductServiceTest {

    @Test
    @DisplayName("French gap label: more, less, same, no Apple price, no grid price")
    void gapLabel() {
        assertThat(StoreProductService.gapLabel(4500L, 3500L, 1000L)).isEqualTo("1 000 FCFA de plus que la grille");
        assertThat(StoreProductService.gapLabel(2500L, 3500L, -1000L)).isEqualTo("1 000 FCFA de moins que la grille");
        assertThat(StoreProductService.gapLabel(3500L, 3500L, 0L)).isEqualTo("Identique à la grille");
        assertThat(StoreProductService.gapLabel(null, 3500L, null)).isEqualTo("Prix App Store non renseigné");
        assertThat(StoreProductService.gapLabel(3500L, null, null))
                .isEqualTo("Aucun prix dans la grille pour cette durée");
    }
}
