package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 16.12 — shape test: a fresh tenant has exactly the committed reference shape
 * ({@link TenantSchemaShape#RESOURCE}), captured from the programmatic provisioner before the tenant DDL moved to
 * the module migrations. {@code -Dkeevo.tenant-shape.capture=true} rewrites the reference from the current code.
 */
@DisplayName("Fresh tenant shape (Story 16.12)")
class TenantShapePostgresIntegrationTest {

    private static final String DATABASE = "keevo_tenant_shape";
    private static final boolean CAPTURE = Boolean.getBoolean("keevo.tenant-shape.capture");

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void database() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(SharedPostgresContainer.freshDatabase(DATABASE));
        dataSource.setUser(SharedPostgresContainer.username());
        dataSource.setPassword(SharedPostgresContainer.password());
    }

    @AfterAll
    static void drop() {
        SharedPostgresContainer.dropDatabase(DATABASE);
    }

    @Test
    @DisplayName("a freshly provisioned tenant matches the reference shape")
    void freshTenantMatchesReference() throws Exception {
        String schema = "kv_shape1";
        new TenantSchemaProvisioner(dataSource).provision("00000000-0000-0000-0000-0000000016c1", schema);
        Map<String, List<String>> shape;
        try (Connection conn = dataSource.getConnection()) {
            shape = TenantSchemaShape.capture(conn, schema);
        }
        if (CAPTURE) {
            TenantSchemaShape.write(shape);
        }
        assertThat(TenantSchemaShape.tables(shape)).hasSize(30); // 19.9: + notifications (messaging V2)
        Map<String, List<String>> reference = TenantSchemaShape.reference();
        assertThat(shape.keySet()).isEqualTo(reference.keySet());
        reference.forEach((kind, lines) -> assertThat(shape.get(kind)).as(kind).containsExactlyElementsOf(lines));
    }
}
