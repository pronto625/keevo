package com.keevo.catalog.product.domain.service;

import com.keevo.shared.domain.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PricingCalculatorTest — TDD tests written BEFORE implementation (RED phase).
 *
 * <p>Pricing rules (XAF integer arithmetic):
 * <ul>
 *   <li>totalCost = buyPrice + transportCost
 *   <li>grossMargin = sellingPrice - totalCost
 *   <li>marginPercentage = (grossMargin / totalCost) * 100  (0 if totalCost == 0) — AC2 ref
 *   <li>isLoss = grossMarginXaf < 0
 * </ul>
 *
 * <p>GoF Pattern: Strategy (PricingCalculator is a pure domain service, dependency-free).
 */
@DisplayName("PricingCalculator domain service")
class PricingCalculatorTest {

    private PricingCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PricingCalculator();
    }

    // ── Normal margin scenarios ─────────────────────────────

    @Test
    @DisplayName("should calculate margin for standard product")
    void should_calculate_margin_for_standard_product() {
        // buyPrice=3000, transportCost=500, sellingPrice=5000
        // totalCost=3500, grossMargin=1500, marginPct=1500/3500*100=42.857% (AC2: base=totalCost)
        Money buyPrice       = new Money(3000);
        Money transportCost  = new Money(500);
        Money sellingPrice   = new Money(5000);

        MarginCalculation result = calculator.calculateMargin(buyPrice, transportCost, sellingPrice);

        assertThat(result.totalCost().value()).isEqualTo(3500);
        assertThat(result.grossMarginXaf()).isEqualTo(1500);
        assertThat(result.marginPercentage()).isCloseTo(42.86, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.isLoss()).isFalse();
    }

    @Test
    @DisplayName("should calculate margin with zero transport cost")
    void should_calculate_margin_with_zero_transport_cost() {
        // buyPrice=4000, transportCost=0, sellingPrice=5000
        // totalCost=4000, grossMargin=1000, marginPct=1000/4000*100=25% (AC2: base=totalCost)
        Money buyPrice       = new Money(4000);
        Money transportCost  = new Money(0);
        Money sellingPrice   = new Money(5000);

        MarginCalculation result = calculator.calculateMargin(buyPrice, transportCost, sellingPrice);

        assertThat(result.totalCost().value()).isEqualTo(4000);
        assertThat(result.grossMarginXaf()).isEqualTo(1000);
        assertThat(result.marginPercentage()).isEqualTo(25.0);
        assertThat(result.isLoss()).isFalse();
    }

    @Test
    @DisplayName("should detect loss when selling price below total cost")
    void should_detect_loss_when_selling_price_below_total_cost() {
        // buyPrice=4000, transportCost=500, sellingPrice=3000
        // totalCost=4500, grossMargin=-1500 → isLoss=true
        Money buyPrice      = new Money(4000);
        Money transportCost = new Money(500);
        Money sellingPrice  = new Money(3000);

        MarginCalculation result = calculator.calculateMargin(buyPrice, transportCost, sellingPrice);

        assertThat(result.totalCost().value()).isEqualTo(4500);
        assertThat(result.isLoss()).isTrue();
        assertThat(result.marginPercentage()).isLessThan(0);
    }

    @Test
    @DisplayName("should return zero margin when selling at exact cost")
    void should_return_zero_margin_when_selling_at_cost() {
        // buyPrice=3000, transportCost=500, sellingPrice=3500
        // totalCost=3500, grossMargin=0, marginPct=0%
        Money buyPrice      = new Money(3000);
        Money transportCost = new Money(500);
        Money sellingPrice  = new Money(3500);

        MarginCalculation result = calculator.calculateMargin(buyPrice, transportCost, sellingPrice);

        assertThat(result.totalCost().value()).isEqualTo(3500);
        assertThat(result.grossMarginXaf()).isEqualTo(0);
        assertThat(result.marginPercentage()).isEqualTo(0.0);
        assertThat(result.isLoss()).isFalse();
    }

    @Test
    @DisplayName("should return zero marginPercentage when totalCost is zero")
    void should_return_zero_margin_percentage_when_selling_price_is_zero() {
        // Edge case: totalCost = 0 (division by zero guard — AC2: base=totalCost)
        Money buyPrice      = new Money(0);
        Money transportCost = new Money(0);
        Money sellingPrice  = new Money(0);

        MarginCalculation result = calculator.calculateMargin(buyPrice, transportCost, sellingPrice);

        assertThat(result.marginPercentage()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("should calculate margin percentage with precision")
    void should_calculate_margin_percentage_with_precision() {
        // buyPrice=2000, transportCost=300, sellingPrice=3000
        // totalCost=2300, grossMargin=700, marginPct=700/2300*100=30.43...% (AC2: base=totalCost)
        Money buyPrice      = new Money(2000);
        Money transportCost = new Money(300);
        Money sellingPrice  = new Money(3000);

        MarginCalculation result = calculator.calculateMargin(buyPrice, transportCost, sellingPrice);

        // 700/2300*100 = 30.434...
        assertThat(result.marginPercentage()).isCloseTo(30.43, org.assertj.core.data.Offset.offset(0.01));
    }

    // ── Null argument guards ────────────────────────────────

    @Test
    @DisplayName("should throw when buyPrice is null")
    void should_throw_when_buy_price_is_null() {
        assertThatThrownBy(() -> calculator.calculateMargin(null, new Money(0), new Money(5000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should throw when transportCost is null")
    void should_throw_when_transport_cost_is_null() {
        assertThatThrownBy(() -> calculator.calculateMargin(new Money(3000), null, new Money(5000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should throw when sellingPrice is null")
    void should_throw_when_selling_price_is_null() {
        assertThatThrownBy(() -> calculator.calculateMargin(new Money(3000), new Money(500), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
