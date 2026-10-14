package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TenantSchemaTest — v1s-13-12 follow-up: the single schema-name gate and its message contract
 * (Story 12.12 D5: length only, never the raw — possibly attacker-controlled — value).
 */
@DisplayName("TenantSchema")
class TenantSchemaTest {

    @Test
    @DisplayName("valid names pass unchanged")
    void validNames() {
        assertThat(TenantSchema.isValid("kv_abc123")).isTrue();
        assertThat(TenantSchema.validate("kv_abc123")).isEqualTo("kv_abc123");
    }

    @Test
    @DisplayName("invalid names are rejected (case, length, prefix, null, injection)")
    void invalidNames() {
        assertThat(TenantSchema.isValid(null)).isFalse();
        assertThat(TenantSchema.isValid("kv_ABC123")).isFalse();
        assertThat(TenantSchema.isValid("kv_abc12")).isFalse();
        assertThat(TenantSchema.isValid("public")).isFalse();
        assertThat(TenantSchema.isValid("kv_abc123; DROP SCHEMA public")).isFalse();
    }

    @Test
    @DisplayName("validate() message carries the length only, never the raw value")
    void messageNeverEchoesValue() {
        String evil = "kv_abc12\"; DROP";
        assertThatThrownBy(() -> TenantSchema.validate(evil))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length=" + evil.length())
                .hasMessageNotContaining("DROP")
                .hasMessageNotContaining(evil);
        assertThatThrownBy(() -> TenantSchema.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length=null");
    }

    @Test
    @DisplayName("W1: only SUPER_ADMIN on the \"public\" system tenant is a platform admin session")
    void platformAdminSession() {
        assertThat(TenantSchema.isPlatformAdminSession("public", "SUPER_ADMIN")).isTrue();
        assertThat(TenantSchema.isPlatformAdminSession("public", "OWNER")).isFalse();
        assertThat(TenantSchema.isPlatformAdminSession("public", "EMPLOYEE")).isFalse();
        assertThat(TenantSchema.isPlatformAdminSession("public", null)).isFalse();
        assertThat(TenantSchema.isPlatformAdminSession("kv_abc123", "SUPER_ADMIN")).isFalse();
        assertThat(TenantSchema.isPlatformAdminSession("PUBLIC", "SUPER_ADMIN")).isFalse();
        assertThat(TenantSchema.isPlatformAdminSession(null, "SUPER_ADMIN")).isFalse();
    }
}
