package com.keevo.catalog.product.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MarginThresholdTest — TDD tests written BEFORE implementation (RED phase).
 *
 * <p>Threshold rules:
 * <ul>
 *   <li>LOSS        : marginPercentage < 0
 *   <li>LOW         : 0 ≤ marginPercentage < 10
 *   <li>MODERATE    : 10 ≤ marginPercentage < 20
 *   <li>PROFITABLE  : marginPercentage ≥ 20
 * </ul>
 */
@DisplayName("MarginThreshold enum")
class MarginThresholdTest {

    @Test
    @DisplayName("should categorize negative margin as LOSS")
    void should_categorize_negative_margin_as_loss() {
        assertThat(MarginThreshold.categorizeMargin(-1.0)).isEqualTo(MarginThreshold.LOSS);
        assertThat(MarginThreshold.categorizeMargin(-50.0)).isEqualTo(MarginThreshold.LOSS);
    }

    @Test
    @DisplayName("should categorize zero margin as LOW")
    void should_categorize_zero_margin_as_low() {
        assertThat(MarginThreshold.categorizeMargin(0.0)).isEqualTo(MarginThreshold.LOW);
    }

    @Test
    @DisplayName("should categorize margin 0-9.99% as LOW")
    void should_categorize_low_margin() {
        assertThat(MarginThreshold.categorizeMargin(5.0)).isEqualTo(MarginThreshold.LOW);
        assertThat(MarginThreshold.categorizeMargin(9.99)).isEqualTo(MarginThreshold.LOW);
    }

    @Test
    @DisplayName("should categorize margin 10-19.99% as MODERATE")
    void should_categorize_moderate_margin() {
        assertThat(MarginThreshold.categorizeMargin(10.0)).isEqualTo(MarginThreshold.MODERATE);
        assertThat(MarginThreshold.categorizeMargin(15.0)).isEqualTo(MarginThreshold.MODERATE);
        assertThat(MarginThreshold.categorizeMargin(19.99)).isEqualTo(MarginThreshold.MODERATE);
    }

    @Test
    @DisplayName("should categorize margin >= 20% as PROFITABLE")
    void should_categorize_profitable_margin() {
        assertThat(MarginThreshold.categorizeMargin(20.0)).isEqualTo(MarginThreshold.PROFITABLE);
        assertThat(MarginThreshold.categorizeMargin(50.0)).isEqualTo(MarginThreshold.PROFITABLE);
        assertThat(MarginThreshold.categorizeMargin(100.0)).isEqualTo(MarginThreshold.PROFITABLE);
    }

    @Test
    @DisplayName("LOSS should have RED color indicator")
    void loss_should_have_red_color() {
        assertThat(MarginThreshold.LOSS.getColor()).isEqualTo("RED");
    }

    @Test
    @DisplayName("LOW should have RED color indicator")
    void low_should_have_red_color() {
        assertThat(MarginThreshold.LOW.getColor()).isEqualTo("RED");
    }

    @Test
    @DisplayName("MODERATE should have ORANGE color indicator")
    void moderate_should_have_orange_color() {
        assertThat(MarginThreshold.MODERATE.getColor()).isEqualTo("ORANGE");
    }

    @Test
    @DisplayName("PROFITABLE should have GREEN color indicator")
    void profitable_should_have_green_color() {
        assertThat(MarginThreshold.PROFITABLE.getColor()).isEqualTo("GREEN");
    }
}
