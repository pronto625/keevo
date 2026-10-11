package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.mockito.Mockito.*;

/**
 * Story v1s-13-12 — the {@code search_path} gate goes through {@link TenantSchema#isValid(String)}:
 * {@code public}, null and malformed identifiers never touch {@code search_path}.
 */
@DisplayName("SchemaAwareMultiTenantConnectionProvider — search_path gate")
class SchemaAwareMultiTenantConnectionProviderTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement stmt = mock(Statement.class);
    private final SchemaAwareMultiTenantConnectionProvider provider =
            new SchemaAwareMultiTenantConnectionProvider(dataSource);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"public", "foo", "kv_ABC123", "kv_abc1234", "kv_abc12\"; DROP"})
    @DisplayName("public / null / invalid → no search_path on get or release")
    void noSearchPathForPublicOrInvalid(String tenant) throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);

        provider.getConnection(tenant);
        provider.releaseConnection(tenant, connection);

        verify(connection, never()).createStatement();
        verify(connection).close();
    }

    @Test
    @DisplayName("valid tenant → SET search_path to tenant on get, back to public on release")
    void searchPathForValidTenant() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(stmt);

        provider.getConnection("kv_abc123");
        provider.releaseConnection("kv_abc123", connection);

        verify(stmt).execute("SET search_path TO \"kv_abc123\", public");
        verify(stmt).execute("SET search_path TO public");
        verify(connection).close();
    }
}
