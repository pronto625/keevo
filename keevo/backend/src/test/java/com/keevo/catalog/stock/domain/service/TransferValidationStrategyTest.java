package com.keevo.catalog.stock.domain.service;

import com.keevo.catalog.stock.domain.port.in.TransferStockCommand;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * TransferValidationStrategyTest — RED phase first.
 * Task 5.1 — Story 3.3.
 */
class TransferValidationStrategyTest {

    private TransferValidationStrategy strategy;

    private static final UUID SRC_ID   = UUID.randomUUID();
    private static final UUID DEST_ID  = UUID.randomUUID();
    private static final UUID PROD_ID  = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        strategy = new DefaultTransferValidationStrategy();
    }

    @Test
    void defaultStrategy_shouldThrow_whenQuantityExceedsStock() {
        var cmd = new TransferStockCommand(SRC_ID, DEST_ID, PROD_ID, null, 10, ACTOR_ID, null);

        assertThatThrownBy(() -> strategy.validate(cmd, 5))
            .isInstanceOf(DomainException.class)
            .extracting("domainCode")
            .isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void defaultStrategy_shouldThrow_whenSameSourceAndDestination() {
        var cmd = new TransferStockCommand(SRC_ID, SRC_ID, PROD_ID, null, 3, ACTOR_ID, null);

        assertThatThrownBy(() -> strategy.validate(cmd, 100))
            .isInstanceOf(DomainException.class)
            .extracting("domainCode")
            .isEqualTo("SAME_SOURCE_DESTINATION");
    }

    @Test
    void defaultStrategy_shouldPass_whenAllValid() {
        var cmd = new TransferStockCommand(SRC_ID, DEST_ID, PROD_ID, null, 5, ACTOR_ID, null);

        assertThatNoException().isThrownBy(() -> strategy.validate(cmd, 10));
    }

    @Test
    void defaultStrategy_shouldThrow_whenExactlyInsufficient() {
        var cmd = new TransferStockCommand(SRC_ID, DEST_ID, PROD_ID, null, 6, ACTOR_ID, null);

        assertThatThrownBy(() -> strategy.validate(cmd, 5))
            .isInstanceOf(DomainException.class)
            .extracting("domainCode")
            .isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void defaultStrategy_shouldPass_whenQuantityEqualsAvailable() {
        var cmd = new TransferStockCommand(SRC_ID, DEST_ID, PROD_ID, null, 5, ACTOR_ID, null);

        assertThatNoException().isThrownBy(() -> strategy.validate(cmd, 5));
    }
}
