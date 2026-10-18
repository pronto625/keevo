package com.keevo.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantLastActivitySql — « Dernière activité » = latest merchant event")
class TenantLastActivitySqlTest {

    @Test
    @DisplayName("reads sales, own users' audit, sync pushes and app use — never SYSTEM writes")
    void expressionSources() {
        String sql = TenantLastActivitySql.expression("kv_abc123");

        assertThat(sql).startsWith("GREATEST(")
                .contains("FROM kv_abc123.sales")
                .contains("FROM kv_abc123.audit_log")
                .contains("FROM kv_abc123.sync_operations_log")
                .contains("FROM public.user_daily_activity")
                // automatic day closure (SYSTEM actor) and schedulers are not merchant activity
                .contains("a.user_id <> '00000000-0000-0000-0000-000000000000'::uuid")
                .contains("a.source <> 'SYSTEM'")
                // only the tenant's own users count (no Super Admin / outsider write)
                .contains("public.user_tenant_memberships")
                .contains("m.role IN ('OWNER', 'EMPLOYEE')");
    }

    @Test
    @DisplayName("binds exactly as many parameters as it declares")
    void argsMatchPlaceholders() {
        UUID id = UUID.randomUUID();
        String sql = TenantLastActivitySql.expression("kv_abc123");
        long placeholders = sql.chars().filter(c -> c == '?').count();

        assertThat(TenantLastActivitySql.args(id)).hasSize((int) placeholders).containsOnly(id);
        assertThat(TenantLastActivitySql.args(id.toString())).containsOnly(id.toString());
    }

    @Test
    @DisplayName("an invalid schema name is rejected before any SQL is built")
    void invalidSchemaRejected() {
        assertThatThrownBy(() -> TenantLastActivitySql.expression("public; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantLastActivitySql.expression(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
