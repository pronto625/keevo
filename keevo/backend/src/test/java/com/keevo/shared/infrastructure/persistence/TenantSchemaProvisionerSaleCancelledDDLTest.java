package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD test verifying the TenantSchemaProvisioner DDL migration constant that
 * widens {@code ck_movement_type} to accept {@code SALE_CANCELLED} (Story v1s-13-5,
 * AC1). Mirrors the existing reflection-based DDL-content test pattern
 * (e.g. {@link TenantSchemaProvisionerStoresDDLTest}) — no Spring context or
 * real database needed to verify the SQL text itself.
 */
class TenantSchemaProvisionerSaleCancelledDDLTest {

    @Test
    @DisplayName("DDL_STOCK_MOVEMENTS_MIGRATE_SALE_CANCELLED must idempotently widen ck_movement_type")
    void should_define_migrate_sale_cancelled_constant() throws Exception {
        Field field = TenantSchemaProvisioner.class
                .getDeclaredField("DDL_STOCK_MOVEMENTS_MIGRATE_SALE_CANCELLED");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertNotNull(sql);
        assertTrue(sql.contains("stock_movements"), "must target stock_movements table");
        assertTrue(sql.contains("ck_movement_type"), "must target ck_movement_type constraint");
        assertTrue(sql.contains("DROP CONSTRAINT IF EXISTS"), "must drop before re-adding (idempotent)");
        assertTrue(sql.contains("SALE_CANCELLED"), "must widen the CHECK to accept SALE_CANCELLED");
        // The full original allowed-value set must be preserved — a migration that
        // narrows the constraint would silently break existing movement types.
        for (String existing : new String[]{"SALE", "STOCK_ENTRY", "TRANSFER_IN", "TRANSFER_OUT", "ADJUSTMENT"}) {
            assertTrue(sql.contains(existing), "must preserve existing movement type: " + existing);
        }
        assertTrue(sql.contains("EXCEPTION WHEN duplicate_object"),
                "must swallow duplicate_object for idempotent re-provisioning (same pattern as "
                        + "DDL_STOCK_TRANSFERS_MIGRATE_IN_TRANSIT)");
    }

    @Test
    @DisplayName("MovementType enum must include SALE_CANCELLED")
    void should_define_sale_cancelled_movement_type() {
        assertDoesNotThrow(() ->
                com.keevo.catalog.stock.domain.entity.MovementType.valueOf("SALE_CANCELLED"));
    }
}
