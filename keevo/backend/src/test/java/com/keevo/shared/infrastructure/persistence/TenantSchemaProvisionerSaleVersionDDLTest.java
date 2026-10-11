package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Story v1s-13-10 — provisioning a tenant schema must add the sales optimistic-lock column
 * ({@code SaleJpaEntity.version}); otherwise Hibernate fails on that tenant's sales.
 */
class TenantSchemaProvisionerSaleVersionDDLTest {

    @Test
    @DisplayName("provision() executes the sales version column DDL")
    void provisionAddsSalesVersionColumn() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(stmt);

        new TenantSchemaProvisioner(dataSource).provision("tenant-1", "kv_ver123");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(stmt, atLeastOnce()).execute(sql.capture());
        assertThat(sql.getAllValues())
                .contains("ALTER TABLE sales ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0");
    }
}
