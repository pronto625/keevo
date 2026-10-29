package com.keevo.shared.event.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.keevo.identity.auth.adapter.in.api.IdentityAuthApiTestSupport;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.event.catalog.contact.ClientCreatedEvent;
import com.keevo.shared.event.catalog.counting.InventoryScopeKind;
import com.keevo.shared.event.catalog.counting.InventorySessionCreatedEvent;
import com.keevo.shared.event.catalog.onboarding.OnboardingCompletedEvent;
import com.keevo.shared.event.catalog.onboarding.SectorKind;
import com.keevo.shared.event.catalog.product.ProductCreatedEvent;
import com.keevo.shared.event.catalog.sale.DayClosedEvent;
import com.keevo.shared.event.catalog.sale.DayClosureSummary;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import com.keevo.shared.event.catalog.stock.MovementKind;
import com.keevo.shared.event.catalog.stock.StockAdjustedEvent;
import com.keevo.shared.event.catalog.store.StoreCreatedEvent;
import com.keevo.shared.event.catalog.store.StoreKind;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.AuditEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Story 12.3 — moving the event contracts to {@code shared.event.catalog} and swapping the carried internal
 * types ({@code MovementType}, {@code SectorType}, {@code InventoryScope}, {@code store.api.StoreType},
 * {@code commerce.api.DayClosureSummary}) for catalogue types must not change any payload.
 *
 * <p>The golden strings below were produced by the same snapshot code run against the pre-12.3 event classes
 * (baseline {@code 4dacb9ad}, internal types, producer packages) and are asserted byte-for-byte here:
 * the Jackson form of the events (what 12.4's publication registry will persist) and the audit row
 * (action, entity, before/after details, metadata) written by {@link AuditEventListener}.
 */
class CatalogueEventPayloadTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STORE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENTITY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TENANT = "kv_abc123";
    private static final Instant AT = Instant.parse("2026-10-29T10:15:30Z");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    static Map<String, Object> representativeEvents() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("StockAdjustedEvent", new StockAdjustedEvent(ENTITY, null, STORE, MovementKind.TRANSFER_OUT,
                10, -3, 7, ACTOR, "casse", TENANT, "OWNER", "Awa", "Akwa", "Riz 5kg", AT, "Bonapriso"));
        events.put("OnboardingCompletedEvent", new OnboardingCompletedEvent(TENANT, SectorKind.FOOD_GROCERY,
                "Mon Épicerie", 6, ACTOR, AT));
        events.put("InventorySessionCreatedEvent", new InventorySessionCreatedEvent(ENTITY, STORE,
                InventoryScopeKind.PARTIAL, ACTOR, TENANT, AT));
        events.put("StoreCreatedEvent", new StoreCreatedEvent(STORE, "Dépôt Akwa", StoreKind.WAREHOUSE, ACTOR,
                TENANT, AT));
        events.put("DayClosedEvent", new DayClosedEvent(ENTITY, STORE, ACTOR,
                new DayClosureSummary(12, 54000, "p-1", "Riz 5kg", 9, 30000, 24000, 2, 8000), false, TENANT, AT,
                AT.minusSeconds(36000), AT));
        events.put("SaleCompletedEvent", new SaleCompletedEvent(ENTITY, ACTOR, TENANT, STORE, 54000, 500,
                "[{\"productId\":\"p-1\",\"qty\":2}]", AT));
        events.put("ProductCreatedEvent", new ProductCreatedEvent(ENTITY, "Riz 5kg", "RIZ-5", TENANT, ACTOR,
                "EMPLOYEE", "Awa", "Akwa", AT));
        events.put("ClientCreatedEvent", new ClientCreatedEvent(ENTITY, "Mme Ngo", ACTOR, TENANT, AT));
        return events;
    }

    /** name → "event=<jackson json>" and "audit=<entry fields>" for each representative event. */
    static Map<String, String> snapshots() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : representativeEvents().entrySet()) {
            Object event = e.getValue();
            out.put(e.getKey() + ".event", mapper.writeValueAsString(event));

            AuditPort auditPort = mock(AuditPort.class);
            AuditEventListener listener = new AuditEventListener(auditPort, new ObjectMapper(),
                    IdentityAuthApiTestSupport.userQueryPort(mock(UserRepository.class)));
            Method on = AuditEventListener.class.getMethod("on", event.getClass());
            on.invoke(listener, event);
            TenantContext.clear();
            ArgumentCaptor<AuditPort.AuditEntry> captor = ArgumentCaptor.forClass(AuditPort.AuditEntry.class);
            verify(auditPort).record(captor.capture());
            out.put(e.getKey() + ".audit", captor.getValue().toString());
        }
        return out;
    }

    @Test
    void cataloguePayloadsAreIdenticalToThePreCatalogueOnes() throws Exception {
        assertThat(snapshots()).containsExactlyEntriesOf(GOLDEN);
    }

    @Test
    void envelopeExposesTheExistingPayloadValues() {
        for (Object event : representativeEvents().values()) {
            DomainEvent envelope = (DomainEvent) event;
            assertThat(envelope.tenantId()).isEqualTo(TENANT);
            assertThat(envelope.occurredAt()).isEqualTo(AT);
            assertThat(envelope.actorId()).isEqualTo(ACTOR);
        }
    }

    /**
     * The catalogue kinds are mapped by name at publication ({@code X.valueOf(internal.name())}): both enums
     * must keep exactly the same constants, in the same order, or a publication would throw / audit JSON drift.
     */
    @Test
    void catalogueKindsHaveTheSameConstantsAsTheInternalTypes() {
        assertSameConstants(com.keevo.inventory.stock.domain.entity.MovementType.class, MovementKind.class);
        assertSameConstants(com.keevo.identity.onboarding.domain.model.SectorType.class, SectorKind.class);
        assertSameConstants(com.keevo.store.api.StoreType.class, StoreKind.class);
        assertSameConstants(com.keevo.inventory.counting.domain.model.InventoryScope.class, InventoryScopeKind.class);
    }

    private static void assertSameConstants(Class<? extends Enum<?>> internal, Class<? extends Enum<?>> kind) {
        assertThat(java.util.Arrays.stream(kind.getEnumConstants()).map(Enum::name).toList())
                .as("%s vs %s", kind.getSimpleName(), internal.getSimpleName())
                .containsExactlyElementsOf(java.util.Arrays.stream(internal.getEnumConstants()).map(Enum::name).toList());
    }

    // Captured from baseline 4dacb9ad (pre-12.3 classes) — see class Javadoc.
    private static final Map<String, String> GOLDEN = new LinkedHashMap<>();

    static {
        GOLDEN.put("StockAdjustedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"actorName\":\"Awa\",\"actorRole\":\"OWNER\",\"counterpartStoreName\":\"Bonapriso\",\"movementType\":\"TRANSFER_OUT\",\"notes\":\"casse\",\"occurredAt\":\"2026-10-29T10:15:30Z\",\"productId\":\"33333333-3333-3333-3333-333333333333\",\"productName\":\"Riz 5kg\",\"quantityAfter\":7,\"quantityBefore\":10,\"quantityChange\":-3,\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"storeName\":\"Akwa\",\"tenantId\":\"kv_abc123\",\"variantId\":null}");
        GOLDEN.put("StockAdjustedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=STOCK_ADJUSTED, entityType=StockMovement, entityId=33333333-3333-3333-3333-333333333333, valueBefore={\"quantityBefore\":10}, valueAfter={\"movementType\":\"TRANSFER_OUT\",\"quantityChange\":-3,\"quantityAfter\":7}, storeId=22222222-2222-2222-2222-222222222222, entityLabel=Riz 5kg, deviceId=null, source=SYSTEM, metadataJson={\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"notes\":\"casse\",\"destinationStoreName\":\"Bonapriso\",\"actorLabel\":\"Awa\"}]");
        GOLDEN.put("OnboardingCompletedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"categoriesCreated\":6,\"occurredAt\":\"2026-10-29T10:15:30Z\",\"sectorType\":\"FOOD_GROCERY\",\"storeName\":\"Mon Épicerie\",\"tenantId\":\"kv_abc123\"}");
        GOLDEN.put("OnboardingCompletedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=ONBOARDING_COMPLETED, entityType=Tenant, entityId=11111111-1111-1111-1111-111111111111, valueBefore=null, valueAfter={\"sectorType\":\"FOOD_GROCERY\",\"storeName\":\"Mon Épicerie\",\"categoriesCreated\":6}, storeId=null, entityLabel=Mon Épicerie, deviceId=null, source=SYSTEM, metadataJson=null]");
        GOLDEN.put("InventorySessionCreatedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"occurredAt\":\"2026-10-29T10:15:30Z\",\"scope\":\"PARTIAL\",\"sessionId\":\"33333333-3333-3333-3333-333333333333\",\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"tenantId\":\"kv_abc123\"}");
        GOLDEN.put("InventorySessionCreatedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=INVENTORY_SESSION_CREATED, entityType=InventorySession, entityId=33333333-3333-3333-3333-333333333333, valueBefore=null, valueAfter={\"status\":\"IN_PROGRESS\"}, storeId=22222222-2222-2222-2222-222222222222, entityLabel=Inventaire, deviceId=null, source=SYSTEM, metadataJson={\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"scope\":\"PARTIAL\",\"occurredAt\":\"2026-10-29T10:15:30Z\"}]");
        GOLDEN.put("StoreCreatedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Dépôt Akwa\",\"occurredAt\":\"2026-10-29T10:15:30Z\",\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"tenantId\":\"kv_abc123\",\"type\":\"WAREHOUSE\"}");
        GOLDEN.put("StoreCreatedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=STORE_CREATED, entityType=Store, entityId=22222222-2222-2222-2222-222222222222, valueBefore=null, valueAfter={\"name\":\"Dépôt Akwa\",\"type\":\"WAREHOUSE\"}, storeId=22222222-2222-2222-2222-222222222222, entityLabel=Dépôt Akwa, deviceId=null, source=SYSTEM, metadataJson=null]");
        GOLDEN.put("DayClosedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"closureId\":\"33333333-3333-3333-3333-333333333333\",\"isAutomatic\":false,\"lateFrom\":null,\"occurredAt\":\"2026-10-29T10:15:30Z\",\"receivedUntil\":null,\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"summary\":{\"cashAmount\":30000,\"momoAmount\":24000,\"pendingSalesCount\":2,\"pendingSalesTotal\":8000,\"topProductId\":\"p-1\",\"topProductName\":\"Riz 5kg\",\"topProductQty\":9,\"totalRevenue\":54000,\"totalSales\":12},\"tenantId\":\"kv_abc123\",\"windowEnd\":\"2026-10-29T10:15:30Z\",\"windowStart\":\"2026-10-29T00:15:30Z\"}");
        GOLDEN.put("DayClosedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=DAY_CLOSED, entityType=DayClosure, entityId=33333333-3333-3333-3333-333333333333, valueBefore=null, valueAfter={\"totalSales\":12,\"totalRevenue\":54000}, storeId=22222222-2222-2222-2222-222222222222, entityLabel=Clôture de journée, deviceId=null, source=SYSTEM, metadataJson={\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"isAutomatic\":false,\"occurredAt\":\"2026-10-29T10:15:30Z\"}]");
        GOLDEN.put("SaleCompletedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"discountAmount\":500,\"itemsSnapshot\":\"[{\\\"productId\\\":\\\"p-1\\\",\\\"qty\\\":2}]\",\"occurredAt\":\"2026-10-29T10:15:30Z\",\"saleId\":\"33333333-3333-3333-3333-333333333333\",\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"tenantId\":\"kv_abc123\",\"totalAmount\":54000}");
        GOLDEN.put("SaleCompletedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=SALE_COMPLETED, entityType=Sale, entityId=33333333-3333-3333-3333-333333333333, valueBefore=null, valueAfter={\"totalAmount\":54000,\"discountAmount\":500,\"itemsSnapshot\":\"[{\\\"productId\\\":\\\"p-1\\\",\\\"qty\\\":2}]\"}, storeId=22222222-2222-2222-2222-222222222222, entityLabel=Vente, deviceId=null, source=SYSTEM, metadataJson={\"storeId\":\"22222222-2222-2222-2222-222222222222\",\"occurredAt\":\"2026-10-29T10:15:30Z\"}]");
        GOLDEN.put("ProductCreatedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"actorName\":\"Awa\",\"actorRole\":\"EMPLOYEE\",\"occurredAt\":\"2026-10-29T10:15:30Z\",\"productId\":\"33333333-3333-3333-3333-333333333333\",\"productName\":\"Riz 5kg\",\"productSku\":\"RIZ-5\",\"storeName\":\"Akwa\",\"tenantId\":\"kv_abc123\"}");
        GOLDEN.put("ProductCreatedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=PRODUCT_CREATED, entityType=Product, entityId=33333333-3333-3333-3333-333333333333, valueBefore=null, valueAfter={\"productName\":\"Riz 5kg\",\"productSku\":\"RIZ-5\"}, storeId=null, entityLabel=Riz 5kg, deviceId=null, source=SYSTEM, metadataJson={\"actorLabel\":\"Awa\"}]");
        GOLDEN.put("ClientCreatedEvent.event",
                "{\"actorId\":\"11111111-1111-1111-1111-111111111111\",\"clientId\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"Mme Ngo\",\"occurredAt\":\"2026-10-29T10:15:30Z\",\"tenantId\":\"kv_abc123\"}");
        GOLDEN.put("ClientCreatedEvent.audit",
                "AuditEntry[actorId=11111111-1111-1111-1111-111111111111, tenantId=kv_abc123, action=CLIENT_CREATED, entityType=Client, entityId=33333333-3333-3333-3333-333333333333, valueBefore=null, valueAfter={\"name\":\"Mme Ngo\"}, storeId=null, entityLabel=Mme Ngo, deviceId=null, source=SYSTEM, metadataJson=null]");
    }
}
