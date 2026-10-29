package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 12.2d — tenant convention of the published module APIs ({@link TenantContext#requireCurrent}). */
class TenantContextRequireCurrentTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void match_passes() {
        TenantContext.setCurrentTenant("kv_abc123");
        assertThatCode(() -> TenantContext.requireCurrent("kv_abc123")).doesNotThrowAnyException();
    }

    @Test
    void mismatch_fails() {
        TenantContext.setCurrentTenant("kv_abc123");
        assertThatThrownBy(() -> TenantContext.requireCurrent("kv_zzz999")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullOrBlank_fails_evenWhenTheContextIsEmptyToo() {
        assertThatThrownBy(() -> TenantContext.requireCurrent(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.requireCurrent(" ")).isInstanceOf(IllegalStateException.class);
        TenantContext.setCurrentTenant("kv_abc123");
        assertThatThrownBy(() -> TenantContext.requireCurrent(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.requireCurrent("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void schemaGivenButNoContext_fails() {
        assertThatThrownBy(() -> TenantContext.requireCurrent("kv_abc123")).isInstanceOf(IllegalStateException.class);
    }
}
