package com.keevo.shared.infrastructure.observability;

import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BusinessMetricsListener — business counters fed by domain events (Story 11.2, MOD-NFR4).
 *
 * <ul>
 *   <li>{@code keevo_sales_total{tenant}} — completed sales;</li>
 *   <li>{@code keevo_sales_revenue_total{tenant}} — gross amount of completed sales (sum of
 *       {@code totalAmount}, cancellations not subtracted; zero/negative amounts ignored).</li>
 * </ul>
 *
 * <p>Counted <b>after commit only</b>: a sale whose transaction rolls back never moves the counters.
 * {@code fallbackExecution} keeps counting a {@link SaleCompletedEvent} published outside a transaction.
 * {@code SaleCompletedEvent} is published once per completed sale (direct record, manual validation,
 * validation cascade); pending sales publish {@code SalePendingValidationEvent} instead — no double count.
 *
 * <p>Cardinality: {@code tenant} = tenant schema name, bounded (&lt; 100 tenants). No per-user,
 * per-sale or per-product tag.
 *
 * <p>Story 12.4: as an after-commit listener it is recorded by the Spring Modulith publication registry
 * (not converted, no receipt). A crash between the sale commit and the counter update leaves the publication
 * incomplete and the replay counts it once more — possible double count after a crash, accepted (low:
 * in-process counters, reset on restart anyway).
 */
@Component
public class BusinessMetricsListener {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetricsListener.class);

    static final String SALES = "keevo.sales";
    static final String SALES_REVENUE = "keevo.sales.revenue";
    static final String TAG_TENANT = "tenant";
    static final String UNKNOWN_TENANT = "unknown";

    private final MeterRegistry meterRegistry;

    public BusinessMetricsListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleCompleted(SaleCompletedEvent event) {
        // fallbackExecution runs this in the publisher's call stack: a metrics failure must never
        // reach the sale flow.
        try {
            String tenant = event.getTenantId() == null || event.getTenantId().isBlank()
                    ? UNKNOWN_TENANT
                    : event.getTenantId();

            Counter.builder(SALES)
                    .description("Completed sales")
                    .tag(TAG_TENANT, tenant)
                    .register(meterRegistry)
                    .increment();

            Counter revenue = Counter.builder(SALES_REVENUE)
                    .description("Gross amount of completed sales (sum of totalAmount; cancellations not subtracted)")
                    .tag(TAG_TENANT, tenant)
                    .register(meterRegistry);
            if (event.getTotalAmount() > 0) {
                revenue.increment(event.getTotalAmount());
            }
        } catch (RuntimeException e) {
            log.warn("METRICS: completed sale not counted: {}", e.getMessage());
        }
    }
}
