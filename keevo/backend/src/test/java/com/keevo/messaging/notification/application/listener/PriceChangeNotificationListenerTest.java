package com.keevo.messaging.notification.application.listener;

import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.identity.api.EmployeeView;
import com.keevo.identity.api.OwnerRecipientResolver;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushNotifier;
import com.keevo.shared.capability.notification.WhatsAppSender;
import com.keevo.shared.event.catalog.product.ProductPriceChangedEvent;
import com.keevo.shared.event.catalog.product.SalePriceOverriddenEvent;
import com.keevo.messaging.notification.application.listener.StockModificationNotificationListenerTest.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 17.7 — owner notification when an employee changes a selling price (catalogue or till). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PriceChangeNotificationListener (Story 17.7)")
class PriceChangeNotificationListenerTest {

    @Mock private PushNotifier pushNotifier;
    @Mock private WhatsAppSender whatsAppSender;
    @Mock private OwnerRecipientResolver ownerRecipientResolver;
    @Mock private EmployeeQueryPort employeeQuery;

    private static final String TENANT = "kv_test123";
    private static final UUID EMPLOYEE_USER = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final long WINDOW_MIN = 5;

    private MutableClock clock;
    private PriceChangeNotificationListener listener;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        listener = new PriceChangeNotificationListener(pushNotifier, whatsAppSender, ownerRecipientResolver,
                employeeQuery, WINDOW_MIN, clock);
        when(employeeQuery.findByUserId(TENANT, EMPLOYEE_USER)).thenReturn(Optional.of(
                new EmployeeView(UUID.randomUUID(), EMPLOYEE_USER, UUID.randomUUID(), "Loïc", "Mbarga", true)));
        when(employeeQuery.findByUserId(TENANT, OWNER_USER)).thenReturn(Optional.empty());
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.of("+237690000000"));
    }

    private ProductPriceChangedEvent catalogue(UUID actor, String role, int oldPrice, int newPrice) {
        return new ProductPriceChangedEvent(PRODUCT, "Riz", oldPrice, newPrice, actor, role, TENANT, NOW);
    }

    private static final UUID SALE = UUID.randomUUID();

    private SalePriceOverriddenEvent till(UUID actor, int catalogue, int applied) {
        return new SalePriceOverriddenEvent(PRODUCT, SALE, "Riz", catalogue, applied, actor, TENANT, NOW);
    }

    private void flushAfterWindow() {
        clock.advance(Duration.ofMinutes(WINDOW_MIN).plusSeconds(1));
        listener.flushExpiredWindows();
    }

    private NotificationPayload pushed() {
        ArgumentCaptor<NotificationPayload> captor = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(pushNotifier).notifyOwners(eq(TENANT), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("employee catalogue edit → single push + WhatsApp after the window")
    void catalogueSingle() {
        listener.onProductPriceChanged(catalogue(EMPLOYEE_USER, "EMPLOYEE", 1500, 1200));

        listener.flushExpiredWindows(); // window still open
        verifyNoInteractions(pushNotifier, whatsAppSender);

        flushAfterWindow();
        NotificationPayload p = pushed();
        String body = "Loïc Mbarga a changé le prix de « Riz » : 1 500 → 1 200 FCFA.";
        assertThat(p.type()).isEqualTo("EMPLOYEE_PRICE_CHANGED");
        assertThat(p.title()).isEqualTo("💰 Prix modifié par Loïc Mbarga");
        assertThat(p.body()).isEqualTo(body);
        assertThat(p.deepLink()).isEqualTo("/products/" + PRODUCT + "/edit");
        assertThat(p.metadata()).containsEntry("productId", PRODUCT.toString()).containsEntry("productName", "Riz")
                .containsEntry("oldPrice", "1500").containsEntry("newPrice", "1200")
                .containsEntry("source", "CATALOGUE").containsEntry("actorId", EMPLOYEE_USER.toString())
                .containsEntry("tenantId", TENANT).doesNotContainKey("saleId");
        verify(whatsAppSender).sendReport("+237690000000", body);
        assertThat(listener.openWindowCount()).isZero();
    }

    @Test
    @DisplayName("employee till override → « a vendu … au lieu de … »")
    void tillSingle() {
        listener.onSalePriceOverridden(till(EMPLOYEE_USER, 1500, 1200));

        flushAfterWindow();
        NotificationPayload p = pushed();
        String body = "Loïc Mbarga a vendu « Riz » à 1 200 FCFA au lieu de 1 500 FCFA.";
        assertThat(p.title()).isEqualTo("💰 Prix modifié à la caisse");
        assertThat(p.body()).isEqualTo(body);
        assertThat(p.deepLink()).isEqualTo("/products/" + PRODUCT + "/edit");
        assertThat(p.metadata()).containsEntry("source", "SALE").containsEntry("oldPrice", "1500")
                .containsEntry("newPrice", "1200").containsEntry("saleId", SALE.toString());
        verify(whatsAppSender).sendReport("+237690000000", body);
    }

    @Test
    @DisplayName("3 till overrides + 1 catalogue edit by one employee → one recap")
    void recap() {
        listener.onSalePriceOverridden(till(EMPLOYEE_USER, 1500, 1200));
        listener.onSalePriceOverridden(till(EMPLOYEE_USER, 800, 700));
        listener.onSalePriceOverridden(till(EMPLOYEE_USER, 2000, 1800));
        listener.onProductPriceChanged(catalogue(EMPLOYEE_USER, "EMPLOYEE", 1500, 1300));

        flushAfterWindow();
        NotificationPayload p = pushed();
        String body = "Loïc Mbarga a modifié 4 prix (1 au catalogue, 3 à la caisse).";
        assertThat(p.type()).isEqualTo("EMPLOYEE_PRICE_CHANGED_BATCH");
        assertThat(p.title()).isEqualTo("💰 Prix modifiés par Loïc Mbarga");
        assertThat(p.body()).isEqualTo(body);
        assertThat(p.deepLink()).isEqualTo("/stock/overview");
        assertThat(p.metadata()).containsEntry("count", "4").containsEntry("catalogueCount", "1")
                .containsEntry("saleCount", "3");
        verify(whatsAppSender).sendReport("+237690000000", body);
    }

    @Test
    @DisplayName("owner catalogue edit → nothing")
    void ownerCatalogueSkipped() {
        listener.onProductPriceChanged(catalogue(OWNER_USER, "OWNER", 1500, 1200));
        flushAfterWindow();
        verifyNoInteractions(pushNotifier, whatsAppSender);
        assertThat(listener.openWindowCount()).isZero();
    }

    @Test
    @DisplayName("owner (not an employee) sells at another price → nothing")
    void ownerTillSkipped() {
        listener.onSalePriceOverridden(till(OWNER_USER, 1500, 1200));
        flushAfterWindow();
        verifyNoInteractions(pushNotifier, whatsAppSender);
    }

    @Test
    @DisplayName("unknown / null seller → nothing")
    void unknownSellerSkipped() {
        listener.onSalePriceOverridden(till(UUID.randomUUID(), 1500, 1200));
        listener.onSalePriceOverridden(till(null, 1500, 1200));
        flushAfterWindow();
        verifyNoInteractions(pushNotifier, whatsAppSender);
    }

    @Test
    @DisplayName("same price → nothing")
    void samePriceSkipped() {
        listener.onProductPriceChanged(catalogue(EMPLOYEE_USER, "EMPLOYEE", 1500, 1500));
        listener.onSalePriceOverridden(till(EMPLOYEE_USER, 1500, 1500));
        flushAfterWindow();
        verifyNoInteractions(pushNotifier, whatsAppSender);
    }

    @Test
    @DisplayName("push fails → WhatsApp still sent, nothing propagated")
    void pushFailure() {
        doThrow(new RuntimeException("FCM down")).when(pushNotifier).notifyOwners(anyString(), any());
        listener.onSalePriceOverridden(till(EMPLOYEE_USER, 1500, 1200));

        flushAfterWindow();
        verify(whatsAppSender).sendReport(eq("+237690000000"), anyString());
    }

    @Test
    @DisplayName("no owner phone → push only")
    void noPhone() {
        when(ownerRecipientResolver.resolveOwnerWhatsAppPhone(TENANT)).thenReturn(Optional.empty());
        listener.onProductPriceChanged(catalogue(EMPLOYEE_USER, "EMPLOYEE", 1500, 1200));

        flushAfterWindow();
        verify(pushNotifier).notifyOwners(eq(TENANT), any());
        verify(whatsAppSender, never()).sendReport(anyString(), anyString());
    }

    @Test
    @DisplayName("employee without a name on file → « Un employé »")
    void fallbackName() {
        UUID other = UUID.randomUUID();
        when(employeeQuery.findByUserId(TENANT, other)).thenReturn(Optional.of(
                new EmployeeView(UUID.randomUUID(), other, null, " ", null, true)));
        listener.onSalePriceOverridden(till(other, 1500, 1200));

        flushAfterWindow();
        assertThat(pushed().body()).startsWith("Un employé a vendu");
    }

    @Test
    @DisplayName("windows are per employee: two employees → two single notifications")
    void perEmployeeWindows() {
        UUID other = UUID.randomUUID();
        when(employeeQuery.findByUserId(TENANT, other)).thenReturn(Optional.of(
                new EmployeeView(UUID.randomUUID(), other, null, "Awa", "Ndiaye", true)));
        listener.onSalePriceOverridden(till(EMPLOYEE_USER, 1500, 1200));
        listener.onSalePriceOverridden(till(other, 1500, 1200));

        flushAfterWindow();
        verify(pushNotifier, org.mockito.Mockito.times(2)).notifyOwners(eq(TENANT), any());
    }

    @Test
    @DisplayName("catalogue change by an EMPLOYEE not found among employees → « Un employé »")
    void catalogueUnknownEmployee_fallsBackToGenericName() {
        listener.onProductPriceChanged(catalogue(UUID.randomUUID(), "EMPLOYEE", 1500, 1200));

        flushAfterWindow();
        assertThat(pushed().body()).isEqualTo("Un employé a changé le prix de « Riz » : 1 500 → 1 200 FCFA.");
    }

    @Test
    @DisplayName("employee lookup fails → handler throws (publication resubmitted), no window opened")
    void lookupFailurePropagates() {
        UUID broken = UUID.randomUUID();
        when(employeeQuery.findByUserId(TENANT, broken)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> listener.onSalePriceOverridden(till(broken, 1500, 1200)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> listener.onProductPriceChanged(catalogue(broken, "EMPLOYEE", 1500, 1200)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(listener.openWindowCount()).isZero();
    }

    @Test
    @DisplayName("amounts are grouped by thousands")
    void formatting() {
        assertThat(PriceChangeNotificationListener.formatXaf(0)).isEqualTo("0 FCFA");
        assertThat(PriceChangeNotificationListener.formatXaf(950)).isEqualTo("950 FCFA");
        assertThat(PriceChangeNotificationListener.formatXaf(1500)).isEqualTo("1 500 FCFA");
        assertThat(PriceChangeNotificationListener.formatXaf(1250000)).isEqualTo("1 250 000 FCFA");
    }

    @Test
    @DisplayName("19.5 — a MANAGER's catalogue edit notifies the owners like an EMPLOYEE's")
    void managerCatalogueEditNotified() {
        listener.onProductPriceChanged(catalogue(EMPLOYEE_USER, "MANAGER", 1500, 1200));
        flushAfterWindow();
        assertThat(pushed().type()).isEqualTo("EMPLOYEE_PRICE_CHANGED");
    }
}
