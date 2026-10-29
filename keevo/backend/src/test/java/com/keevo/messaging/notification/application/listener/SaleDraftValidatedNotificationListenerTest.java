package com.keevo.messaging.notification.application.listener;

import com.keevo.shared.infrastructure.event.ModuleListenerTestSupport;
import com.keevo.identity.onboarding.adapter.in.api.IdentityOnboardingApiTestSupport;
import com.keevo.identity.auth.adapter.in.api.IdentityAuthApiTestSupport;
import com.keevo.store.store.adapter.in.api.StoreApiTestSupport;
import com.keevo.identity.employee.adapter.in.api.IdentityEmployeeApiTestSupport;
import com.keevo.commerce.sale.adapter.in.api.CommerceSaleApiTestSupport;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.shared.event.catalog.sale.SaleDraftProductsUpgradedEvent;
import com.keevo.commerce.sale.domain.model.SaleItem;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;
import com.keevo.identity.api.StockAlertChannel;
import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.store.store.domain.model.Store;
import com.keevo.store.store.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * v1s-12-10: the "draft products sold" owner notification depends on the event's
 * {@code draftsPromoted} flag (seller role set by SaleSyncHandler), never on the Employee row.
 * OWNER seller (true) → drafts were promoted at checkout ("activés").
 * EMPLOYEE seller (false) → drafts are NOT promoted any more; they await validation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleDraftValidatedNotificationListenerTest {

    @Mock private SaleRepository saleRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private UserRepository userRepository;
    @Mock private OwnerRecipientResolver ownerRecipientResolver;
    @Mock private TenantPreferencesRepository tenantPreferencesRepository;
    @Mock private PushNotifier pushNotifier;
    @Mock private WhatsAppSender whatsAppSender;

    private SaleDraftValidatedNotificationListener listener;

    private static final String TENANT = "kv_abc123";
    private final UUID saleId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID draftId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Story 12.4: wrapped in the production module-listener interceptor (tenant bound from the event).
        listener = ModuleListenerTestSupport.bound(new SaleDraftValidatedNotificationListener(
                CommerceSaleApiTestSupport.saleQueryPort(saleRepository), IdentityEmployeeApiTestSupport.queryPort(employeeRepository),
                StoreApiTestSupport.queryPort(storeRepository), IdentityAuthApiTestSupport.userQueryPort(userRepository), ownerRecipientResolver, IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository), pushNotifier, whatsAppSender));

        SaleItem item = mock(SaleItem.class);
        when(item.getProductId()).thenReturn(draftId);
        when(item.getProductName()).thenReturn("Savon karité");
        Sale sale = mock(Sale.class);
        when(sale.getEmployeeId()).thenReturn(sellerId);
        when(sale.getStoreId()).thenReturn(storeId);
        when(sale.getItems()).thenReturn(List.of(item));
        when(saleRepository.findById(saleId)).thenReturn(Optional.of(sale));

        Store store = mock(Store.class);
        when(store.name()).thenReturn("Boutique Akwa");
        when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.empty()); // → PUSH
    }

    private NotificationPayload fire(boolean draftsPromoted) {
        listener.onSaleDraftProductsUpgraded(new SaleDraftProductsUpgradedEvent(
                saleId, storeId, sellerId, List.of(draftId), 5000, TENANT, Instant.now(),
                draftsPromoted));
        var captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier).notifyOwners(eq(TENANT), captor.capture());
        return captor.getValue();
    }

    @Test
    void employeeSeller_saysDraftsAwaitValidation() {
        Employee employee = mock(Employee.class);
        when(employee.getFirstName()).thenReturn("Loïc");
        when(employee.getLastName()).thenReturn("Mbarga");
        when(employeeRepository.findByUserId(sellerId)).thenReturn(Optional.of(employee));

        NotificationPayload payload = fire(false);

        assertThat(payload.title()).isEqualTo("🆕 Produits brouillons vendus");
        assertThat(payload.body()).startsWith("Loïc Mbarga — Boutique Akwa : Savon karité");
        assertThat(payload.body()).endsWith("(brouillons à valider)");
    }

    @Test
    void employeeSellerWithoutEmployeeRow_stillSaysDraftsAwaitValidation() {
        when(employeeRepository.findByUserId(sellerId)).thenReturn(Optional.empty());
        when(userRepository.findById(sellerId)).thenReturn(Optional.empty());

        NotificationPayload payload = fire(false);

        assertThat(payload.title()).isEqualTo("🆕 Produits brouillons vendus");
        assertThat(payload.body()).endsWith("(brouillons à valider)");
    }

    @Test
    void ownerSellerWithEmployeeRow_keepsActivatedWording() {
        Employee employee = mock(Employee.class);
        when(employee.getFirstName()).thenReturn("Awa");
        when(employee.getLastName()).thenReturn("Ngo");
        when(employeeRepository.findByUserId(sellerId)).thenReturn(Optional.of(employee));

        NotificationPayload payload = fire(true);

        assertThat(payload.title()).isEqualTo("🆕 Nouveaux produits activés");
        assertThat(payload.body()).endsWith("(produits brouillons activés lors d'une vente)");
    }

    @Test
    void ownerSeller_keepsActivatedWording() {
        when(employeeRepository.findByUserId(sellerId)).thenReturn(Optional.empty());
        when(userRepository.findById(sellerId)).thenReturn(Optional.empty());

        NotificationPayload payload = fire(true);

        assertThat(payload.title()).isEqualTo("🆕 Nouveaux produits activés");
        assertThat(payload.body()).endsWith("(produits brouillons activés lors d'une vente)");
        verify(whatsAppSender, never()).sendReport(any(), any());
    }

    /** v1s-14-17 — BOTH channel: the owner WhatsApp message goes to the resolved owner phone. */
    @Test
    void bothChannel_sendsWhatsAppToResolvedOwnerPhone() {
        var defaults = TenantPreferences.withDefaults(
                UUID.randomUUID(), SectorType.FOOD_GROCERY, "20:00:00", true, Instant.now());
        var bothPrefs = new TenantPreferences(
                defaults.id(), defaults.sectorType(), defaults.eodReportTime(), true, defaults.createdAt(),
                defaults.eodReportEnabled(), defaults.eodReportChannel(),
                defaults.weeklyReportEnabled(), defaults.weeklyReportDay(),
                defaults.weeklyReportTime(), defaults.weeklyReportChannel(),
                defaults.inventoryReportEnabled(), defaults.inventoryReportChannel(),
                StockAlertChannel.BOTH, defaults.trendNotificationEnabled());
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(bothPrefs));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.of("+237600000001"));

        fire(true);

        verify(whatsAppSender).sendReport(eq("+237600000001"), anyString());
    }

    /** Story 12.4 (R2) — both channels attempted, then the provider failure propagates and the receipt is released. */
    @Test
    void sendFailures_propagate_bothChannelsAttempted_receiptReleased() {
        var defaults = TenantPreferences.withDefaults(
                UUID.randomUUID(), SectorType.FOOD_GROCERY, "20:00:00", true, Instant.now());
        var bothPrefs = new TenantPreferences(
                defaults.id(), defaults.sectorType(), defaults.eodReportTime(), true, defaults.createdAt(),
                defaults.eodReportEnabled(), defaults.eodReportChannel(),
                defaults.weeklyReportEnabled(), defaults.weeklyReportDay(),
                defaults.weeklyReportTime(), defaults.weeklyReportChannel(),
                defaults.inventoryReportEnabled(), defaults.inventoryReportChannel(),
                StockAlertChannel.BOTH, defaults.trendNotificationEnabled());
        when(tenantPreferencesRepository.findByCurrentTenant()).thenReturn(Optional.of(bothPrefs));
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.of("+237600000001"));
        when(employeeRepository.findByUserId(sellerId)).thenReturn(Optional.empty());
        when(userRepository.findById(sellerId)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("fcm down")).when(pushNotifier).notifyOwners(any(), any());
        doThrow(new RuntimeException("wa down")).when(whatsAppSender).sendReport(any(), any());

        var receipts = mock(com.keevo.shared.infrastructure.event.EventListenerReceipts.class);
        when(receipts.tryAcquire(anyString(), anyString())).thenReturn(true);
        var guarded = ModuleListenerTestSupport.bound(new SaleDraftValidatedNotificationListener(
                CommerceSaleApiTestSupport.saleQueryPort(saleRepository), IdentityEmployeeApiTestSupport.queryPort(employeeRepository),
                StoreApiTestSupport.queryPort(storeRepository), IdentityAuthApiTestSupport.userQueryPort(userRepository),
                ownerRecipientResolver, IdentityOnboardingApiTestSupport.preferencesQueryPort(tenantPreferencesRepository),
                pushNotifier, whatsAppSender), receipts);

        assertThatThrownBy(() -> guarded.onSaleDraftProductsUpgraded(new SaleDraftProductsUpgradedEvent(
                saleId, storeId, sellerId, List.of(draftId), 5000, TENANT, Instant.now(), true)))
                .isInstanceOf(com.keevo.shared.event.DeliveryFailedException.class)
                .hasRootCauseMessage("fcm down")
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
        verify(whatsAppSender).sendReport(eq("+237600000001"), anyString());
        verify(receipts).release(anyString(), anyString());
    }
}
