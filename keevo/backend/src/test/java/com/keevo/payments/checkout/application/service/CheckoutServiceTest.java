package com.keevo.payments.checkout.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantView;
import com.keevo.payments.checkout.adapter.out.gateway.DisabledMobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.OfferPricing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CheckoutService — Mobile Money disabled (Story 21.1)")
class CheckoutServiceTest {

    @Test
    @DisplayName("gateway disabled, paid quote → 503 PAYMENT_UNAVAILABLE, nothing stored")
    void disabledGateway() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        OfferPricing pricing = mock(OfferPricing.class);
        TenantQueryPort tenants = mock(TenantQueryPort.class);
        PaymentRequestStore store = mock(PaymentRequestStore.class);
        UUID tenantId = UUID.randomUUID();
        when(tenants.findBySchemaName("kv_abc123")).thenReturn(Optional.of(new TenantView(tenantId, "KV-ABC123",
                "kv_abc123", "Test", TenantStatus.ACTIVE, PlanType.FREE, now, null)));
        when(pricing.quote(any(), any(), any(), any())).thenReturn(new OfferPricing.Quote("PRO", "Pro", "MONTHLY",
                "Mensuel", 1, 3500, 0, 3500, 3500, null, now));
        CheckoutService service = new CheckoutService(pricing, null, tenants, store, null,
                new DisabledMobileMoneyGateway(), null, null, new PaymentSettings(Duration.ofMinutes(2), Duration.ofMinutes(10), ""),
                TransactionOperations.withoutTransaction(), Clock.fixed(now, ZoneOffset.UTC), null);

        assertThatThrownBy(() -> service.start(new CheckoutService.StartCommand("kv_abc123", UUID.randomUUID(),
                "PRO", "MONTHLY", null, "MTN_MOMO", "677000111")))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                            .isEqualTo(ErrorCode.PAYMENT_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(e.getUserMessage())
                            .isEqualTo("Le paiement Mobile Money est momentanément indisponible");
                });
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).insert(any());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).expireStale(any(), any(), any(), any());
    }

    // ── Story 21.3: account credit while Mobile Money is down ───────────────

    private static CheckoutService creditService(Instant now, PaymentRequestStore store,
                                                 com.keevo.payments.credit.domain.port.out.AccountCreditStore credits,
                                                 PaymentConfirmationService confirmation, UUID tenantId) {
        OfferPricing pricing = mock(OfferPricing.class);
        TenantQueryPort tenants = mock(TenantQueryPort.class);
        when(tenants.findBySchemaName("kv_abc123")).thenReturn(Optional.of(new TenantView(tenantId, "KV-ABC123",
                "kv_abc123", "Test", TenantStatus.ACTIVE, PlanType.FREE, now, null)));
        when(pricing.quote(any(), any(), any(), any())).thenReturn(new OfferPricing.Quote("PRO", "Pro", "MONTHLY",
                "Mensuel", 1, 3500, 0, 3500, 3500, null, now));
        return new CheckoutService(pricing, null, tenants, store, credits, new DisabledMobileMoneyGateway(),
                confirmation, null, new PaymentSettings(Duration.ofMinutes(2), Duration.ofMinutes(10), ""),
                TransactionOperations.withoutTransaction(), Clock.fixed(now, ZoneOffset.UTC), null);
    }

    @Test
    @DisplayName("21.3 — gateway disabled, credit covers the price → confirmed without the provider; credit short → "
            + "503, nothing stored, no credit taken")
    void creditWhileGatewayDown() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        UUID tenantId = UUID.randomUUID();
        PaymentRequestStore store = mock(PaymentRequestStore.class);
        var credits = mock(com.keevo.payments.credit.domain.port.out.AccountCreditStore.class);
        PaymentConfirmationService confirmation = mock(PaymentConfirmationService.class);
        java.util.concurrent.atomic.AtomicReference<com.keevo.payments.checkout.domain.model.PaymentRequest> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return null;
        }).when(confirmation).confirmCovered(any());
        when(store.find(any(), any())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(credits.lockBalance(tenantId)).thenReturn(5000L);
        CheckoutService service = creditService(now, store, credits, confirmation, tenantId);

        PaymentView covered = service.start(new CheckoutService.StartCommand("kv_abc123", UUID.randomUUID(), "PRO",
                "MONTHLY", null, null, null, null, true));
        org.assertj.core.api.Assertions.assertThat(covered.creditAppliedXaf()).isEqualTo(3500);
        org.assertj.core.api.Assertions.assertThat(covered.amountXaf()).isZero();
        org.assertj.core.api.Assertions.assertThat(stored.get().provider())
                .isEqualTo(com.keevo.payments.checkout.domain.model.PaymentProvider.NONE);

        when(credits.lockBalance(tenantId)).thenReturn(1000L);
        assertThatThrownBy(() -> service.start(new CheckoutService.StartCommand("kv_abc123", UUID.randomUUID(),
                "PRO", "MONTHLY", null, "MTN_MOMO", "677000111", null, true)))
                .isInstanceOfSatisfying(DomainException.class, e -> org.assertj.core.api.Assertions
                        .assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_UNAVAILABLE));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).insert(any());
        org.mockito.Mockito.verify(confirmation, org.mockito.Mockito.never()).reserve(any());
        org.mockito.Mockito.verify(credits, org.mockito.Mockito.never()).debit(any(), org.mockito.ArgumentMatchers
                .anyLong(), any(), any(), any(), any());
    }
}
