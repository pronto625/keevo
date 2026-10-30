package com.keevo.shared.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.admin.subscription.domain.model.TenantReactivatedEvent;
import com.keevo.admin.subscription.domain.model.TenantSuspendedEvent;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.event.catalog.plan.PlanActivatedEvent;
import com.keevo.shared.event.catalog.plan.PlanDowngradedEvent;
import com.keevo.shared.event.catalog.product.CsvImportCompletedEvent;
import com.keevo.shared.event.catalog.product.ProductCreatedEvent;
import com.keevo.shared.event.catalog.stock.MovementKind;
import com.keevo.shared.event.catalog.stock.StockAdjustedEvent;
import com.keevo.shared.event.catalog.stock.StockThresholdBreachedEvent;
import com.keevo.shared.event.catalog.sale.DayClosedEvent;
import com.keevo.shared.event.catalog.sale.DayClosureSummary;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import com.keevo.shared.event.catalog.sale.SaleDraftProductsUpgradedEvent;
import com.keevo.shared.event.catalog.sale.SaleManuallyValidatedEvent;
import com.keevo.shared.event.catalog.sale.SalePendingValidationEvent;
import com.keevo.shared.event.catalog.transfer.TransferCreatedEvent;
import com.keevo.subscription.plan.domain.model.event.PlanExpiredEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 12.4 — every event that reaches an after-commit listener is persisted by the publication registry
 * (Jackson, the application's {@code ObjectMapper}) when it is published, and read back on a replay.
 * A type that cannot be serialized would make {@code publishEvent} — hence the business transaction —
 * fail; a lossy round trip would replay a different event, and a non-identical re-serialization would
 * prevent the registry from marking the publication completed (it matches on the serialized form).
 *
 * <p>The set of event types is discovered from the code (every {@code @TransactionalEventListener}
 * AFTER_COMMIT method, including the {@code @ApplicationModuleListener}s): a new one without a fixture here
 * fails the test.
 */
class RegistryEventSerializationTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TENANT = "kv_abc123";
    private static final Instant T0 = Instant.parse("2026-10-29T10:15:30.123456789Z");
    private static final Instant T1 = Instant.parse("2026-10-29T22:59:59Z");

    private static ObjectMapper mapper;

    @BeforeAll
    static void applicationObjectMapper() {
        AtomicReference<ObjectMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(ctx -> ref.set(ctx.getBean(ObjectMapper.class)));
        mapper = ref.get();
    }

    static Map<Class<?>, Object> fixtures() {
        Map<Class<?>, Object> f = new LinkedHashMap<>();
        f.put(ProductCreatedEvent.class, new ProductCreatedEvent(A, "Riz 5kg", "RIZ-5", TENANT, B,
                "EMPLOYEE", "Awa", "Akwa", T0));
        f.put(SaleManuallyValidatedEvent.class, new SaleManuallyValidatedEvent(A, B, "client fidèle",
                List.of(C, B), TENANT, T0, C));
        f.put(SalePendingValidationEvent.class, new SalePendingValidationEvent(A, B, C, List.of(C), 4500,
                TENANT, T0));
        f.put(SaleDraftProductsUpgradedEvent.class, new SaleDraftProductsUpgradedEvent(A, B, C, List.of(C, A),
                9000, TENANT, T0, true));
        f.put(TransferCreatedEvent.class, new TransferCreatedEvent(A, B, C, A, 12, B, TENANT, T0));
        f.put(DayClosedEvent.class, new DayClosedEvent(A, B, C,
                new DayClosureSummary(12, 54000, "p-1", "Riz 5kg", 9, 30000, 24000, 2, 8000), true, TENANT, T0,
                T0.minusSeconds(36000), T1, T0.minusSeconds(3600), T1.plusSeconds(60)));
        f.put(PlanActivatedEvent.class, new PlanActivatedEvent(A, B, TENANT, PlanType.FREE, T1, 15000, T0));
        f.put(PlanDowngradedEvent.class, new PlanDowngradedEvent(A, B, TENANT, PlanType.PREMIUM, "impayé", T0));
        f.put(PlanExpiredEvent.class, new PlanExpiredEvent(B, TENANT, PlanType.PREMIUM_TRIAL, T0));
        f.put(TenantSuspendedEvent.class, new TenantSuspendedEvent(A, B, TENANT, "fraude", T0));
        f.put(TenantReactivatedEvent.class, new TenantReactivatedEvent(A, B, TENANT, T0));
        f.put(SaleCompletedEvent.class, new SaleCompletedEvent(A, B, TENANT, C, 54000, 500,
                "[{\"productId\":\"p-1\",\"qty\":2}]", T0, A, "MOBILE_MONEY",
                List.of(new SaleCompletedEvent.Line("Riz 5kg", 27000, 2))));
        // 12.6a: the four notification listeners converted to module listeners
        f.put(StockThresholdBreachedEvent.class, new StockThresholdBreachedEvent(A, "Savon", B, 3, 5, C, TENANT, T0));
        f.put(StockAdjustedEvent.class, new StockAdjustedEvent(A, null, B, MovementKind.ADJUSTMENT, 10, -2, 8, C,
                "casse", TENANT, "EMPLOYEE", "Awa", "Akwa", "Riz 5kg", T0, "Bonamoussadi"));
        f.put(CsvImportCompletedEvent.class, new CsvImportCompletedEvent(7, TENANT, A, "Awa", "EMPLOYEE", T0));
        return f;
    }

    @Test
    @DisplayName("every after-commit listener event type has a round-trip fixture")
    void everyRegistryEventTypeIsCovered() {
        assertThat(fixtures().keySet()).containsAll(afterCommitEventTypes());
    }

    @Test
    @DisplayName("each registry event survives serialize → deserialize → serialize unchanged")
    void roundTrip() throws Exception {
        for (Map.Entry<Class<?>, Object> e : fixtures().entrySet()) {
            Object event = e.getValue();
            String json = mapper.writeValueAsString(event);
            Object back = mapper.readValue(json, e.getKey());

            assertThat(mapper.writeValueAsString(back)).as("re-serialized %s", e.getKey().getSimpleName())
                    .isEqualTo(json);
            if (e.getKey().isRecord()) {
                assertThat(back).as("deserialized %s", e.getKey().getSimpleName()).isEqualTo(event);
            }
        }
    }

    @Test
    @DisplayName("14.4: a sale.completed stored before 14.4 (no client / payment mode / lines) still deserializes")
    void legacySaleCompletedPayloadDeserializes() throws Exception {
        String legacy = "{\"actorId\":\"22222222-2222-2222-2222-222222222222\",\"discountAmount\":500,"
                + "\"itemsSnapshot\":\"[]\",\"occurredAt\":\"2026-10-29T10:15:30Z\","
                + "\"saleId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"storeId\":\"33333333-3333-3333-3333-333333333333\",\"tenantId\":\"kv_abc123\","
                + "\"totalAmount\":54000}";

        SaleCompletedEvent back = mapper.readValue(legacy, SaleCompletedEvent.class);

        assertThat(back.getSaleId()).isEqualTo(A);
        assertThat(back.getTotalAmount()).isEqualTo(54000);
        assertThat(back.getClientId()).isNull();
        assertThat(back.getPaymentMode()).isNull();
        assertThat(back.getLines()).isNull();
    }

    @Test
    @DisplayName("14.4: the typed sale lines survive the round trip")
    void saleCompletedLinesRoundTrip() throws Exception {
        SaleCompletedEvent event = (SaleCompletedEvent) fixtures().get(SaleCompletedEvent.class);

        SaleCompletedEvent back = mapper.readValue(mapper.writeValueAsString(event), SaleCompletedEvent.class);

        assertThat(back.getClientId()).isEqualTo(A);
        assertThat(back.getPaymentMode()).isEqualTo("MOBILE_MONEY");
        assertThat(back.getLines()).containsExactly(new SaleCompletedEvent.Line("Riz 5kg", 27000, 2));
    }

    @Test
    @DisplayName("the tenant accessors added for the registry are not serialized (payload unchanged)")
    void tenantAccessorIsNotAPayloadField() throws Exception {
        for (Object event : fixtures().values()) {
            assertThat(mapper.writeValueAsString(event)).doesNotContain("tenantSchema");
        }
    }

    /** Parameter types of every AFTER_COMMIT transactional listener method under {@code com.keevo}. */
    static Set<Class<?>> afterCommitEventTypes() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<Class<?>> types = new HashSet<>();
        scanner.findCandidateComponents("com.keevo").forEach(bd -> {
            try {
                for (Method m : Class.forName(bd.getBeanClassName()).getDeclaredMethods()) {
                    TransactionalEventListener ann =
                            AnnotatedElementUtils.findMergedAnnotation(m, TransactionalEventListener.class);
                    if (ann != null && ann.phase() == TransactionPhase.AFTER_COMMIT && m.getParameterCount() == 1) {
                        types.add(m.getParameterTypes()[0]);
                    }
                }
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(types).as("discovered after-commit event types").isNotEmpty();
        return types;
    }
}
