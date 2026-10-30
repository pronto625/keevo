package com.keevo.shared.infrastructure.observability;

import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BusinessMetricsListenerTest — Story 11.2: {@code keevo_sales_total{tenant}} and
 * {@code keevo_sales_revenue_total{tenant}} per completed sale, after commit only. The rollback row is
 * proven end-to-end in {@code PrometheusScrapeEndpointIntegrationTest}.
 */
@DisplayName("BusinessMetricsListener (sales counters)")
class BusinessMetricsListenerTest {

    private static SaleCompletedEvent sale(String tenant, int total) {
        return new SaleCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), tenant,
                UUID.randomUUID(), total, 0, "[]", Instant.now(), null, "CASH", java.util.List.of());
    }

    @Test
    @DisplayName("sale committed → sales +1 and revenue + totalAmount, per tenant")
    void countsSalesAndRevenuePerTenant() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetricsListener listener = new BusinessMetricsListener(registry);

        listener.onSaleCompleted(sale("kv_aaa111", 7500));
        listener.onSaleCompleted(sale("kv_aaa111", 2500));
        listener.onSaleCompleted(sale("kv_bbb222", 1000));

        assertThat(registry.get("keevo.sales").tag("tenant", "kv_aaa111").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("keevo.sales.revenue").tag("tenant", "kv_aaa111").counter().count())
                .isEqualTo(10_000.0);
        assertThat(registry.get("keevo.sales").tag("tenant", "kv_bbb222").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("missing tenant → tag 'unknown' (a null tag value would throw)")
    void nullTenantTaggedUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new BusinessMetricsListener(registry).onSaleCompleted(sale(null, 100));

        assertThat(registry.get("keevo.sales").tag("tenant", "unknown").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("listens AFTER_COMMIT with fallback execution — a rolled-back sale is never counted")
    void listensAfterCommitOnly() throws Exception {
        TransactionalEventListener annotation = BusinessMetricsListener.class
                .getMethod("onSaleCompleted", SaleCompletedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isTrue();
    }

    @Test
    @DisplayName("Prometheus exposition names: keevo_sales_total / keevo_sales_revenue_total{tenant}")
    void prometheusNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new BusinessMetricsListener(registry).onSaleCompleted(sale("kv_test123", 7500));
        String scrape = registry.scrape();

        assertThat(scrape).contains("keevo_sales_total{tenant=\"kv_test123\"} 1.0");
        assertThat(scrape).contains("keevo_sales_revenue_total{tenant=\"kv_test123\"} 7500.0");
    }

    @Test
    @DisplayName("zero or negative totalAmount → sale counted, revenue unchanged")
    void nonPositiveAmountNotAddedToRevenue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetricsListener listener = new BusinessMetricsListener(registry);

        listener.onSaleCompleted(sale("kv_neg", -500));
        listener.onSaleCompleted(sale("kv_neg", 0));

        assertThat(registry.get("keevo.sales").tag("tenant", "kv_neg").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("keevo.sales.revenue").tag("tenant", "kv_neg").counter().count()).isZero();
    }

    @Test
    @DisplayName("a metrics failure never propagates into the sale flow")
    void metricsFailureSwallowed() {
        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("registry down"));

        assertThatCode(
                () -> new BusinessMetricsListener(broken).onSaleCompleted(sale("kv_x", 100)))
                .doesNotThrowAnyException();
    }
}
