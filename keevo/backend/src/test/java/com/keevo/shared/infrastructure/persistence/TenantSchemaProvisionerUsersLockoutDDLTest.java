package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Story v1s-13-12 — the tenant {@code users} DDL must carry the login-lockout columns on its
 * own (not rely on the request-time public→tenant column sync), and pre-existing tenants get
 * them through idempotent ALTERs right after the CREATE.
 */
class TenantSchemaProvisionerUsersLockoutDDLTest {

    @Test
    @DisplayName("provision(): CREATE TABLE users includes failed_attempts + locked_until, then the idempotent ALTERs run")
    void provisionCreatesUsersWithLockoutColumns() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(stmt);

        new TenantSchemaProvisioner(dataSource).provision("tenant-1", "kv_usr123");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(stmt, atLeastOnce()).execute(sql.capture());
        List<String> all = sql.getAllValues();

        String usersDdl = all.stream()
                .filter(s -> s.contains("CREATE TABLE IF NOT EXISTS users"))
                .findFirst().orElseThrow();
        assertThat(usersDdl)
                .containsPattern("failed_attempts\\s+INTEGER\\s+NOT NULL DEFAULT 0")
                .containsPattern("locked_until\\s+TIMESTAMPTZ");

        int create = all.indexOf(usersDdl);
        assertThat(all.get(create + 1)).isEqualTo(TenantSchemaProvisioner.DDL_USERS_MIGRATE_FAILED_ATTEMPTS);
        assertThat(all.get(create + 2)).isEqualTo(TenantSchemaProvisioner.DDL_USERS_MIGRATE_LOCKED_UNTIL);
        assertThat(TenantSchemaProvisioner.DDL_USERS_MIGRATE_FAILED_ATTEMPTS)
                .isEqualTo("ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_attempts INTEGER NOT NULL DEFAULT 0");
        assertThat(TenantSchemaProvisioner.DDL_USERS_MIGRATE_LOCKED_UNTIL)
                .isEqualTo("ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ");
    }

    @Test
    @DisplayName("invalid / null schema names keep each method's contract (throw / no-op / IAE)")
    void invalidSchemaContractsUnchanged() {
        DataSource dataSource = mock(DataSource.class);
        TenantSchemaProvisioner provisioner = new TenantSchemaProvisioner(dataSource);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provisioner.provision("t", "foo"))
                .isInstanceOf(com.keevo.shared.domain.exception.DomainException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provisioner.dropSchemaForDeletion("foo"))
                .isInstanceOf(IllegalArgumentException.class);
        provisioner.dropSchemaIfExists("foo");
        provisioner.dropSchemaIfExists(null);
        verifyNoInteractions(dataSource);
    }
}
