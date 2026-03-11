package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for contact-related DDL constants in TenantSchemaProvisioner (Story 2.5).
 *
 * Verifies that DDL_CLIENTS, DDL_SUPPLIERS, DDL_PRODUCT_SUPPLIERS,
 * DDL_SALES, DDL_SALES_MIGRATE_CLIENT_ID and their companion indexes
 * are correctly defined.
 */
@DisplayName("TenantSchemaProvisioner — Contact DDL (Story 2.5)")
class TenantSchemaProvisionerContactDDLTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String getStaticField(String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = TenantSchemaProvisioner.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    // ── clients ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DDL_CLIENTS")
    class ClientsDdl {

        @Test
        @DisplayName("DDL_CLIENTS — defines clients table with all required columns")
        void should_define_clients_table_with_all_columns()
                throws NoSuchFieldException, IllegalAccessException {
            String ddl = getStaticField("DDL_CLIENTS");

            assertNotNull(ddl);
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS clients"),
                    "must use CREATE TABLE IF NOT EXISTS clients");
            assertTrue(ddl.contains("id") && ddl.contains("UUID"),
                    "must have UUID id column");
            assertTrue(ddl.contains("name"),         "must have name column");
            assertTrue(ddl.contains("phone"),        "must have phone column");
            assertTrue(ddl.contains("email"),        "must have email column");
            assertTrue(ddl.contains("notes"),        "must have notes column");
            assertTrue(ddl.contains("archived"),     "must have archived column");
            assertTrue(ddl.contains("created_at"),   "must have created_at column");
            assertTrue(ddl.contains("updated_at"),   "must have updated_at column");
        }

        @Test
        @DisplayName("DDL_CLIENTS_IDX_NAME — creates idx_clients_name index")
        void should_define_clients_idx_name()
                throws NoSuchFieldException, IllegalAccessException {
            String idx = getStaticField("DDL_CLIENTS_IDX_NAME");

            assertNotNull(idx);
            assertTrue(idx.contains("CREATE INDEX IF NOT EXISTS idx_clients_name"),
                    "index must be named idx_clients_name");
            assertTrue(idx.contains("clients"), "index must be on clients table");
            assertTrue(idx.contains("name"),    "index must cover name column");
        }

        @Test
        @DisplayName("DDL_CLIENTS_IDX_ARCHIVED — creates idx_clients_archived index")
        void should_define_clients_idx_archived()
                throws NoSuchFieldException, IllegalAccessException {
            String idx = getStaticField("DDL_CLIENTS_IDX_ARCHIVED");

            assertNotNull(idx);
            assertTrue(idx.contains("CREATE INDEX IF NOT EXISTS idx_clients_archived"),
                    "index must be named idx_clients_archived");
            assertTrue(idx.contains("clients"),  "index must be on clients table");
            assertTrue(idx.contains("archived"), "index must cover archived column");
        }
    }

    // ── suppliers ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DDL_SUPPLIERS")
    class SuppliersDdl {

        @Test
        @DisplayName("DDL_SUPPLIERS — defines suppliers table with all required columns")
        void should_define_suppliers_table_with_all_columns()
                throws NoSuchFieldException, IllegalAccessException {
            String ddl = getStaticField("DDL_SUPPLIERS");

            assertNotNull(ddl);
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS suppliers"),
                    "must use CREATE TABLE IF NOT EXISTS suppliers");
            assertTrue(ddl.contains("id") && ddl.contains("UUID"),
                    "must have UUID id column");
            assertTrue(ddl.contains("name"),       "must have name column");
            assertTrue(ddl.contains("phone"),      "must have phone column");
            assertTrue(ddl.contains("email"),      "must have email column");
            assertTrue(ddl.contains("archived"),   "must have archived column");
            assertTrue(ddl.contains("created_at"), "must have created_at column");
            assertTrue(ddl.contains("updated_at"), "must have updated_at column");
        }

        @Test
        @DisplayName("DDL_SUPPLIERS_IDX_NAME — creates idx_suppliers_name index")
        void should_define_suppliers_idx_name()
                throws NoSuchFieldException, IllegalAccessException {
            String idx = getStaticField("DDL_SUPPLIERS_IDX_NAME");

            assertNotNull(idx);
            assertTrue(idx.contains("CREATE INDEX IF NOT EXISTS idx_suppliers_name"),
                    "index must be named idx_suppliers_name");
            assertTrue(idx.contains("suppliers"), "index must be on suppliers table");
            assertTrue(idx.contains("name"),      "index must cover name column");
        }
    }

    // ── product_suppliers ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("DDL_PRODUCT_SUPPLIERS")
    class ProductSuppliersDdl {

        @Test
        @DisplayName("DDL_PRODUCT_SUPPLIERS — defines join table with FKs")
        void should_define_product_suppliers_join_table()
                throws NoSuchFieldException, IllegalAccessException {
            String ddl = getStaticField("DDL_PRODUCT_SUPPLIERS");

            assertNotNull(ddl);
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS product_suppliers"),
                    "must use CREATE TABLE IF NOT EXISTS product_suppliers");
            assertTrue(ddl.contains("product_id"),  "must have product_id column");
            assertTrue(ddl.contains("supplier_id"), "must have supplier_id column");
            assertTrue(ddl.contains("PRIMARY KEY"), "must define composite primary key");
            assertTrue(ddl.contains("REFERENCES products"),
                    "product_id must reference products(id)");
            assertTrue(ddl.contains("REFERENCES suppliers"),
                    "supplier_id must reference suppliers(id)");
        }

        @Test
        @DisplayName("DDL_PRODUCT_SUPPLIERS_IDX_SUPPLIER — creates idx on supplier_id")
        void should_define_product_suppliers_idx_supplier()
                throws NoSuchFieldException, IllegalAccessException {
            String idx = getStaticField("DDL_PRODUCT_SUPPLIERS_IDX_SUPPLIER");

            assertNotNull(idx);
            assertTrue(idx.contains("CREATE INDEX IF NOT EXISTS idx_product_suppliers_supplier"),
                    "index must be named idx_product_suppliers_supplier");
            assertTrue(idx.contains("product_suppliers"), "index must be on product_suppliers table");
            assertTrue(idx.contains("supplier_id"),       "index must cover supplier_id column");
        }
    }

    // ── sales ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DDL_SALES")
    class SalesDdl {

        @Test
        @DisplayName("DDL_SALES — defines sales table with client_id nullable FK")
        void should_define_sales_table_with_client_id()
                throws NoSuchFieldException, IllegalAccessException {
            String ddl = getStaticField("DDL_SALES");

            assertNotNull(ddl);
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS sales"),
                    "must use CREATE TABLE IF NOT EXISTS sales");
            assertTrue(ddl.contains("id") && ddl.contains("UUID"),
                    "must have UUID id column");
            assertTrue(ddl.contains("store_id"),    "must have store_id column");
            assertTrue(ddl.contains("employee_id"), "must have employee_id column");
            assertTrue(ddl.contains("client_id"),   "must have client_id column");
            assertTrue(ddl.contains("total_amount"), "must have total_amount column");
            assertTrue(ddl.contains("payment_mode"), "must have payment_mode column");
            assertTrue(ddl.contains("created_at"),   "must have created_at column");
        }

        @Test
        @DisplayName("DDL_SALES_MIGRATE_CLIENT_ID — idempotent ALTER for client_id")
        void should_define_sales_migrate_client_id_as_idempotent_alter()
                throws NoSuchFieldException, IllegalAccessException {
            String migration = getStaticField("DDL_SALES_MIGRATE_CLIENT_ID");

            assertNotNull(migration);
            assertTrue(migration.contains("ALTER TABLE sales"),
                    "must ALTER TABLE sales");
            assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS"),
                    "must use ADD COLUMN IF NOT EXISTS for idempotency");
            assertTrue(migration.contains("client_id"), "must add client_id column");
            assertTrue(migration.contains("UUID"),      "client_id must be UUID type");
            assertTrue(migration.contains("REFERENCES clients"),
                    "client_id must reference clients table");
        }

        @Test
        @DisplayName("DDL_SALES_IDX_CLIENT — creates idx_sales_client_id index")
        void should_define_sales_idx_client()
                throws NoSuchFieldException, IllegalAccessException {
            String idx = getStaticField("DDL_SALES_IDX_CLIENT");

            assertNotNull(idx);
            assertTrue(idx.contains("CREATE INDEX IF NOT EXISTS idx_sales_client_id"),
                    "index must be named idx_sales_client_id");
            assertTrue(idx.contains("sales"),     "index must be on sales table");
            assertTrue(idx.contains("client_id"), "index must cover client_id column");
        }
    }
}
