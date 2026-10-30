package com.keevo.messaging.notification.application.listener;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.shared.event.catalog.product.CsvImportCompletedEvent;
import com.keevo.shared.event.catalog.product.ProductCreatedEvent;
import com.keevo.shared.event.catalog.stock.StockAdjustedEvent;
import com.keevo.shared.event.catalog.stock.StockThresholdBreachedEvent;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.module.ModuleScoped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Story 13.10a — owner alerts are the paid « Alertes et notifications » module: the stock-alert listener (async
 * module listener, real context) is not invoked for a Free tenant (the publication is completed), and is for a
 * tenant on its registration trial. The first thing the listener does is read the tenant's preferences — the spied
 * port tells whether it ran. The placement of {@code @ModuleScoped("alerts")} on the other owner notifications is
 * checked by reflection (the CSV import feedback is not an alert).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("alerts module gate on owner-notification listeners — full context (Story 13.10a)")
class AlertsModuleScopedFullContextTest extends FullContextIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired TenantFactory tenantFactory;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean TenantPreferencesQueryPort preferences;

    private String schema;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        schema = tx.execute(s -> tenantFactory.create()).getSchemaName();
        tenantId = jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM public.tenant_modules WHERE tenant_id = ?", tenantId); // test tenant only
    }

    private UUID publishBreach() {
        UUID productId = UUID.randomUUID();
        tx.executeWithoutResult(s -> publisher.publishEvent(new StockThresholdBreachedEvent(productId, "Savon",
                UUID.randomUUID(), 1, 5, UUID.randomUUID(), schema, Instant.now())));
        return productId;
    }

    private Object awaitCompletion(UUID productId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT completion_date FROM public.event_publication "
                    + "WHERE listener_id LIKE ? AND serialized_event LIKE ?", "%StockAlertNotificationListener%",
                    "%" + productId + "%");
            if (!rows.isEmpty() && rows.get(0).get("completion_date") != null) {
                return rows.get(0).get("completion_date");
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    @DisplayName("Free tenant → stock alert listener not invoked, publication completed")
    void freeTenantSkipped() throws InterruptedException {
        jdbc.update("UPDATE \"" + schema + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");

        UUID productId = publishBreach();

        assertThat(awaitCompletion(productId)).isNotNull();
        verify(preferences, never()).find(schema);
    }

    @Test
    @DisplayName("Registration trial → stock alert listener invoked")
    void trialDelivered() throws InterruptedException {
        UUID productId = publishBreach();

        assertThat(awaitCompletion(productId)).isNotNull();
        verify(preferences, timeout(5000)).find(schema);
    }

    @Test
    @DisplayName("@ModuleScoped(\"alerts\") on the owner alerts, not on the CSV import feedback")
    void placement() throws NoSuchMethodException {
        assertThat(StockAlertNotificationListener.class.getMethod("onStockThresholdBreached",
                StockThresholdBreachedEvent.class).getAnnotation(ModuleScoped.class).value()).isEqualTo("alerts");
        assertThat(StockModificationNotificationListener.class.getMethod("onStockAdjusted", StockAdjustedEvent.class)
                .getAnnotation(ModuleScoped.class).value()).isEqualTo("alerts");
        assertThat(ProductCreationNotificationListener.class.getMethod("onProductCreated", ProductCreatedEvent.class)
                .getAnnotation(ModuleScoped.class).value()).isEqualTo("alerts");
        assertThat(ProductCreationNotificationListener.class.getMethod("onCsvImportCompleted",
                CsvImportCompletedEvent.class).getAnnotation(ModuleScoped.class)).isNull();
        assertThat(ProductCreationNotificationListener.class.getAnnotation(ModuleScoped.class)).isNull();
    }
}
