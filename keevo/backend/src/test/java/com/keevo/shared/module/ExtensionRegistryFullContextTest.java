package com.keevo.shared.module;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.7 — the real {@link ExtensionRegistry} on the shipped catalogue (production beans and manifests,
 * PostgreSQL): POS declares {@code pos.checkout.post-sale-actions}; no module contributes to it yet (Invoicing is
 * 14.x), so a tenant with POS active gets an empty slot.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ExtensionRegistry — shipped catalogue, full context (Story 13.7)")
class ExtensionRegistryFullContextTest extends FullContextIntegrationTest {

    private static final String SLOT = "pos.checkout.post-sale-actions";

    @Autowired ExtensionRegistry extensions;
    @Autowired ModuleRegistry modules;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("declaredPoints contains the POS slot; a tenant with POS active gets no contribution")
    void shippedSlot() {
        assertThat(extensions.declaredPoints()).containsExactly(SLOT);
        String schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        assertThat(modules.moduleState(schema, "pos")).isEqualTo(ModuleState.ENABLED);
        assertThat(extensions.contributions(SLOT, schema)).isEmpty();
        assertThat(extensions.contributions(SLOT, "public")).isEmpty();
        assertThatThrownBy(() -> extensions.contributions("foo.bar", schema))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
