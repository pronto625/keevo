package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.SaleStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SaleStatusTest {

    @Test
    void SaleStatus_PENDING_VALIDATION_exists() {
        assertThat(SaleStatus.valueOf("PENDING_VALIDATION")).isEqualTo(SaleStatus.PENDING_VALIDATION);
    }

    @Test
    void SaleStatus_allValues_includesThreeStatuses() {
        Set<SaleStatus> expected = Set.of(
                SaleStatus.COMPLETED,
                SaleStatus.CANCELLED,
                SaleStatus.PENDING_VALIDATION
        );
        assertThat(Set.of(SaleStatus.values())).isEqualTo(expected);
    }
}
