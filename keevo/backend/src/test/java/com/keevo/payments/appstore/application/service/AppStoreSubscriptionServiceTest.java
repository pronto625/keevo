package com.keevo.payments.appstore.application.service;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantView;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.payments.appstore.adapter.out.revenuecat.DisabledRevenueCatClient;
import com.keevo.payments.appstore.domain.port.out.ExternalSubscriptionStore;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.PlanAdministrationPort;
import com.keevo.subscription.api.StoreProducts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AppStoreSubscriptionService — sync without RevenueCat keys (Story 21.2)")
class AppStoreSubscriptionServiceTest {

    @Test
    @DisplayName("client disabled → 503 PAYMENT_UNAVAILABLE in French, nothing written")
    void syncDisabled() {
        ExternalSubscriptionStore store = mock(ExternalSubscriptionStore.class);
        TenantQueryPort tenants = mock(TenantQueryPort.class);
        when(tenants.findBySchemaName("kv_abc123")).thenReturn(Optional.of(new TenantView(UUID.randomUUID(),
                "KV-ABC123", "kv_abc123", "Boutique", TenantStatus.ACTIVE, PlanType.FREE, Instant.now(), null)));
        PlanAdministrationPort plans = mock(PlanAdministrationPort.class);
        AppStoreSubscriptionService service = new AppStoreSubscriptionService(store, mock(StoreProducts.class), plans,
                mock(PlanCatalog.class), tenants, mock(UserQueryPort.class), new DisabledRevenueCatClient(),
                new AppStoreSettings("", false, null), mock(ApplicationEventPublisher.class),
                mock(PlatformTransactionManager.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.sync("kv_abc123", UUID.randomUUID()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(e.getUserMessage())
                            .isEqualTo("Les achats App Store sont momentanément indisponibles");
                });
        verifyNoInteractions(store, plans);
    }
}
