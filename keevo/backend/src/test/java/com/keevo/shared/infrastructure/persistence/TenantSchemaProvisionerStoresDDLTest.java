package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD test verifying TenantSchemaProvisioner DDL constants for the stores table
 * include the address, phone, and type columns added in Story 3.1.
 */
class TenantSchemaProvisionerStoresDDLTest {

    @Test
    @DisplayName("DDL_STORES should include address, phone, and type columns")
    void should_define_ddl_stores_with_all_columns() throws Exception {
        Field field = TenantSchemaProvisioner.class.getDeclaredField("DDL_STORES");
        field.setAccessible(true);
        String ddl = (String) field.get(null);

        assertNotNull(ddl);
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS stores"), "must create stores table");
        assertTrue(ddl.contains("address"), "must include address column");
        assertTrue(ddl.contains("phone"), "must include phone column");
        assertTrue(ddl.contains("type"), "must include type column");
        assertTrue(ddl.contains("STORE"), "type CHECK must include STORE");
        assertTrue(ddl.contains("WAREHOUSE"), "type CHECK must include WAREHOUSE");
    }

    @Test
    @DisplayName("DDL_STORES_MIGRATE_ADDRESS must be an ALTER TABLE ADD COLUMN IF NOT EXISTS")
    void should_define_migrate_address_constant() throws Exception {
        Field field = TenantSchemaProvisioner.class.getDeclaredField("DDL_STORES_MIGRATE_ADDRESS");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertNotNull(sql);
        assertTrue(sql.contains("ALTER TABLE"), "must be ALTER TABLE");
        assertTrue(sql.contains("stores"), "must target stores table");
        assertTrue(sql.contains("address"), "must add address column");
        assertTrue(sql.contains("IF NOT EXISTS"), "must be idempotent");
    }

    @Test
    @DisplayName("DDL_STORES_MIGRATE_PHONE must be an ALTER TABLE ADD COLUMN IF NOT EXISTS")
    void should_define_migrate_phone_constant() throws Exception {
        Field field = TenantSchemaProvisioner.class.getDeclaredField("DDL_STORES_MIGRATE_PHONE");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertNotNull(sql);
        assertTrue(sql.contains("ALTER TABLE"), "must be ALTER TABLE");
        assertTrue(sql.contains("stores"), "must target stores table");
        assertTrue(sql.contains("phone"), "must add phone column");
        assertTrue(sql.contains("IF NOT EXISTS"), "must be idempotent");
    }

    @Test
    @DisplayName("DDL_STORES_MIGRATE_TYPE must be an ALTER TABLE ADD COLUMN IF NOT EXISTS with CHECK")
    void should_define_migrate_type_constant() throws Exception {
        Field field = TenantSchemaProvisioner.class.getDeclaredField("DDL_STORES_MIGRATE_TYPE");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertNotNull(sql);
        assertTrue(sql.contains("ALTER TABLE"), "must be ALTER TABLE");
        assertTrue(sql.contains("stores"), "must target stores table");
        assertTrue(sql.contains("type"), "must add type column");
        assertTrue(sql.contains("IF NOT EXISTS"), "must be idempotent");
    }

    @Test
    @DisplayName("DDL_STORES_IDX_TYPE must create an index on stores.type")
    void should_define_idx_type_constant() throws Exception {
        Field field = TenantSchemaProvisioner.class.getDeclaredField("DDL_STORES_IDX_TYPE");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertNotNull(sql);
        assertTrue(sql.contains("CREATE INDEX"), "must create an index");
        assertTrue(sql.contains("stores"), "must target stores table");
        assertTrue(sql.contains("type"), "must index type column");
    }
}
