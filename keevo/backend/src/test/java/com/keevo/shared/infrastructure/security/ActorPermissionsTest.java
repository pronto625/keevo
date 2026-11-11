package com.keevo.shared.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.5b — the fine rights of each fixed role, read from the SHIPPED manifests through the single evaluator: the
 * default mapping that story 19-14 will make editable.
 */
@DisplayName("ActorPermissions — fine rights per role from the shipped manifests (Story 19.5b)")
class ActorPermissionsTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "OWNER,STORE_SALES_VIEW,true", "MANAGER,STORE_SALES_VIEW,true",
            "EMPLOYEE,STORE_SALES_VIEW,false", "VIEWER,STORE_SALES_VIEW,true",
            "OWNER,INVENTORY_SESSION_MANAGE,true", "MANAGER,INVENTORY_SESSION_MANAGE,true",
            "EMPLOYEE,INVENTORY_SESSION_MANAGE,false", "VIEWER,INVENTORY_SESSION_MANAGE,false",
            "OWNER,PRODUCT_MERGE,true", "MANAGER,PRODUCT_MERGE,true",
            "EMPLOYEE,PRODUCT_MERGE,false", "VIEWER,PRODUCT_MERGE,false",
            "OWNER,TEAM_MANAGE,true", "MANAGER,TEAM_MANAGE,true",
            "EMPLOYEE,TEAM_MANAGE,false", "VIEWER,TEAM_MANAGE,false",
            "EMPLOYEE,INVOICE_CREATE,true", "EMPLOYEE,INVOICE_MANAGE,false", "MANAGER,INVOICE_MANAGE,false",
            "VIEWER,INVOICE_CREATE,false", "SUPER_ADMIN,TEAM_MANAGE,false"
    })
    void defaultMapping(String role, String permission, boolean held) {
        ShippedActorPermissions.authenticate(UUID.randomUUID(), role, null);

        assertThat(ShippedActorPermissions.get().currentActorHolds(permission)).isEqualTo(held);
    }

    @Test
    void unauthenticatedHoldsNothing() {
        ActorPermissions permissions = ShippedActorPermissions.get();

        assertThat(permissions.currentActorHolds("TEAM_MANAGE")).isFalse();
        assertThat(permissions.holds(null, "TEAM_MANAGE")).isFalse();
    }

    @Test
    @DisplayName("every permission constant asked through ActorPermissions is declared by a shipped manifest")
    void callSiteConstantsAreDeclared() {
        ActorPermissions permissions = ShippedActorPermissions.get();
        for (String permission : java.util.List.of(
                com.keevo.commerce.sale.adapter.in.SalesReadScope.PERMISSION,
                com.keevo.identity.employee.adapter.in.rest.EmployeeTeamScope.PERMISSION,
                com.keevo.inventory.counting.adapter.in.rest.InventorySessionController.MANAGE_PERMISSION,
                com.keevo.catalog.product.adapter.in.web.ProductController.MERGE_PERMISSION,
                com.keevo.invoicing.invoice.application.usecase.DocumentCancelPermission.QUOTE_PERMISSION,
                PurchasePriceVisibility.PERMISSION)) {
            assertThat(permissions.isDeclared(permission)).as(permission).isTrue();
        }
        assertThat(permissions.isDeclared("TEAM_MANAG")).isFalse();
    }

    @Test
    void withoutMappingBean_onlyTheOwnerHolds() {
        ActorPermissions bare = new ActorPermissions(null);
        ShippedActorPermissions.authenticate(UUID.randomUUID(), "MANAGER", UUID.randomUUID());
        assertThat(bare.currentActorHolds("TEAM_MANAGE")).isFalse();
        ShippedActorPermissions.authenticate(UUID.randomUUID(), "OWNER", null);
        assertThat(bare.currentActorHolds("TEAM_MANAGE")).isTrue();
    }
}
