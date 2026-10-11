package com.keevo.commerce.sale.adapter.out.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** updated_at is the sales pull cursor: any update must move it, whatever the write path. */
class SaleJpaEntityUpdatedAtTest {

    @Test
    void update_alwaysMovesUpdatedAt() {
        var entity = new SaleJpaEntity();
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        entity.setUpdatedAt(old);

        entity.touchUpdatedAtOnUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(old);
    }

    @Test
    void insert_keepsAnExplicitUpdatedAt() {
        var entity = new SaleJpaEntity();
        Instant set = Instant.parse("2026-01-01T00:00:00Z");
        entity.setUpdatedAt(set);

        entity.touchUpdatedAtOnInsert();

        assertThat(entity.getUpdatedAt()).isEqualTo(set);
    }
}
