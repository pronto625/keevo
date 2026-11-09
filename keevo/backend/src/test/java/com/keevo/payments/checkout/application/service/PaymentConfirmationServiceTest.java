package com.keevo.payments.checkout.application.service;

import com.keevo.payments.checkout.domain.model.NextStep;
import com.keevo.payments.checkout.domain.model.PaymentKind;
import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import com.keevo.payments.credit.domain.port.out.AccountCreditStore;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.UsageCredits;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.subscription.api.OfferPricing;
import com.keevo.subscription.api.PlanAdministrationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PaymentConfirmationService — credit-covered checkout and replays (Story 21.3)")
class PaymentConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    private final PaymentRequestStore store = mock(PaymentRequestStore.class);
    private final OfferPricing pricing = mock(OfferPricing.class);
    private final AccountCreditStore credits = mock(AccountCreditStore.class);
    private final PaymentConfirmationService service = new PaymentConfirmationService(store,
            mock(PlanAdministrationPort.class), pricing, PlanCatalog.SEED, mock(ApplicationEventPublisher.class),
            Clock.fixed(NOW, ZoneOffset.UTC), credits, mock(UsageCredits.class));

    private static PaymentRequest request(PaymentStatus status, long credit, long due) {
        return new PaymentRequest(UUID.randomUUID(), UUID.randomUUID(), "kv_abc123", UUID.randomUUID(),
                PaymentProvider.NONE, null, null, "PRO", "Pro", "MONTHLY", "Mensuel", 1, 3500, 500, due, "XAF",
                UUID.randomUUID(), "Bienvenue", "BIENVENUE", NOW, status, NextStep.NONE, null, "KV-x", "ref", null, null,
                null, null, null, null, NOW, PaymentKind.PLAN, credit, null, null, null, 0);
    }

    @Test
    @DisplayName("credit-covered checkout with a capped promo code already used up → 422, nothing stored, no credit "
            + "taken")
    void coveredPromoUsedUp() {
        when(pricing.recordRedemption(any(), anyBoolean())).thenReturn(false);
        assertThatThrownBy(() -> service.confirmCovered(request(PaymentStatus.INITIATING, 3000, 0)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getUserMessage()).isEqualTo("Code promo invalide ou expiré"));
        verify(store, never()).insert(any());
        verify(credits, never()).debit(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a late payment already added to the credit: a replay answers CREDITED and writes nothing")
    void creditedReplay() {
        PaymentRequest r = request(PaymentStatus.CANCELLED, 1000, 2000);
        when(store.lock(r.id())).thenReturn(Optional.of(r));
        when(credits.convertedToCredit(r.id())).thenReturn(true);
        assertThat(service.confirm(r.id())).isEqualTo(PaymentConfirmationService.Outcome.CREDITED);
        verify(credits, never()).credit(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
        verify(store, never()).confirm(any(), any(), any(), any());
    }
}
