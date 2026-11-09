package com.keevo.payments.checkout.application.service;

import com.keevo.payments.checkout.domain.model.MobileMoneyMethod;
import com.keevo.payments.checkout.domain.model.NextStep;
import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.model.PaymentRequest;
import com.keevo.payments.checkout.domain.model.PaymentStatus;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.CallbackResult;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.ProviderStatus;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.StatusResult;
import com.keevo.payments.checkout.domain.port.out.PaymentRequestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 21.1 — the provider's server-to-server status decides, never the callback body: settlement and webhook with a
 * mocked gateway whose status differs from the callback.
 */
@DisplayName("PaymentSettlementService / PaymentWebhookService — provider status is the proof (Story 21.1)")
class PaymentSettlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final String HASH = "secret-hash";

    private final MobileMoneyGateway gateway = mock(MobileMoneyGateway.class);
    private final PaymentRequestStore store = mock(PaymentRequestStore.class);
    private final PaymentConfirmationService confirmation = mock(PaymentConfirmationService.class);
    private PaymentSettlementService settlement;
    private PaymentWebhookService webhooks;

    @BeforeEach
    void setUp() {
        when(gateway.provider()).thenReturn(PaymentProvider.MYCOOLPAY);
        when(gateway.available()).thenReturn(true);
        settlement = new PaymentSettlementService(gateway, store, confirmation);
        webhooks = new PaymentWebhookService(gateway, store, settlement,
                new PaymentSettings(Duration.ofMinutes(2), Duration.ofMinutes(10), HASH));
    }

    private static PaymentRequest request(PaymentStatus status, String providerRef) {
        return new PaymentRequest(UUID.randomUUID(), UUID.randomUUID(), "kv_abc123", UUID.randomUUID(),
                PaymentProvider.MYCOOLPAY, MobileMoneyMethod.MTN_MOMO, "+237677000111", "PRO", "Pro", "MONTHLY",
                "Mensuel", 1, 3500, 0, 3500, "XAF", null, null, null, NOW, status, NextStep.USSD, null,
                "KV-abc", providerRef, null, null, null, null, null, null, NOW);
    }

    private void signedCallback(String ref, ProviderStatus status, String appRef) {
        when(gateway.verifyCallback(any())).thenReturn(new CallbackResult(true, ref, status, appRef));
    }

    @Test
    @DisplayName("signed SUCCESS but provider still PENDING → KO, nothing confirmed")
    void providerNotConfirmed() {
        PaymentRequest r = request(PaymentStatus.PENDING, "MCP-1");
        signedCallback("MCP-1", ProviderStatus.SUCCESS, null);
        when(store.findByProviderRef(PaymentProvider.MYCOOLPAY, "MCP-1")).thenReturn(Optional.of(r));
        when(gateway.status("MCP-1")).thenReturn(new StatusResult(ProviderStatus.PENDING, null, "XAF"));
        assertThat(webhooks.handle(HASH, Map.of())).isEqualTo(PaymentWebhookService.Result.REJECTED);
        verify(confirmation, never()).confirm(any());
    }

    @Test
    @DisplayName("provider unreachable → RETRY (the controller answers 503)")
    void providerUnreachable() {
        PaymentRequest r = request(PaymentStatus.PENDING, "MCP-1");
        signedCallback("MCP-1", ProviderStatus.SUCCESS, null);
        when(store.findByProviderRef(PaymentProvider.MYCOOLPAY, "MCP-1")).thenReturn(Optional.of(r));
        when(gateway.status("MCP-1")).thenThrow(new MobileMoneyGateway.GatewayException("timeout"));
        assertThat(webhooks.handle(HASH, Map.of())).isEqualTo(PaymentWebhookService.Result.RETRY);
        verify(confirmation, never()).confirm(any());
    }

    @Test
    @DisplayName("provider SUCCESS in another currency or below the amount due → NOT_VERIFIED; enough XAF → confirmed")
    void amountAndCurrency() {
        PaymentRequest r = request(PaymentStatus.PENDING, "MCP-1");
        when(gateway.status("MCP-1")).thenReturn(new StatusResult(ProviderStatus.SUCCESS, 3500L, "EUR"));
        assertThat(settlement.settle(r)).isEqualTo(PaymentSettlementService.Settlement.NOT_VERIFIED);
        when(gateway.status("MCP-1")).thenReturn(new StatusResult(ProviderStatus.SUCCESS, 3499L, "XAF"));
        assertThat(settlement.settle(r)).isEqualTo(PaymentSettlementService.Settlement.NOT_VERIFIED);
        verify(confirmation, never()).confirm(any());
        when(gateway.status("MCP-1")).thenReturn(new StatusResult(ProviderStatus.SUCCESS, 3500L, "xaf"));
        when(confirmation.confirm(r.id())).thenReturn(PaymentConfirmationService.Outcome.CONFIRMED);
        assertThat(settlement.settle(r)).isEqualTo(PaymentSettlementService.Settlement.CONFIRMED);
    }

    @Test
    @DisplayName("provider FAILED → in-flight request FAILED with the French reason")
    void providerFailed() {
        PaymentRequest r = request(PaymentStatus.PENDING, "MCP-1");
        when(gateway.status("MCP-1")).thenReturn(new StatusResult(ProviderStatus.FAILED, null, "XAF"));
        assertThat(settlement.settle(r)).isEqualTo(PaymentSettlementService.Settlement.FAILED);
        verify(store).markFailed(eq(r.id()), eq(PaymentStatus.IN_FLIGHT), eq("Le paiement n'a pas abouti"),
                eq("FAILED"));
    }

    @Test
    @DisplayName("callback whose provider ref Keevo never stored → routed by our reference, ref attached, settled")
    void appRefFallback() {
        PaymentRequest failed = request(PaymentStatus.FAILED, null);
        PaymentRequest attached = request(PaymentStatus.FAILED, "MCP-9");
        signedCallback("MCP-9", ProviderStatus.SUCCESS, "KV-abc");
        when(store.findByProviderRef(PaymentProvider.MYCOOLPAY, "MCP-9")).thenReturn(Optional.empty());
        when(store.findByAppRef("KV-abc")).thenReturn(Optional.of(failed));
        when(store.find(failed.id())).thenReturn(Optional.of(attached));
        when(gateway.status("MCP-9")).thenReturn(new StatusResult(ProviderStatus.SUCCESS, 3500L, "XAF"));
        when(confirmation.confirm(any())).thenReturn(PaymentConfirmationService.Outcome.CONFIRMED);
        assertThat(webhooks.handle(HASH, Map.of())).isEqualTo(PaymentWebhookService.Result.ACCEPTED);
        verify(store).attachProviderRef(failed.id(), "MCP-9");
        verify(confirmation).confirm(attached.id());
    }

    @Test
    @DisplayName("unknown references (provider and ours) → IGNORED; foreign app ref (not KV-) never looked up")
    void unknown() {
        signedCallback("MCP-X", ProviderStatus.SUCCESS, "bref-123");
        when(store.findByProviderRef(any(), anyString())).thenReturn(Optional.empty());
        assertThat(webhooks.handle(HASH, Map.of())).isEqualTo(PaymentWebhookService.Result.IGNORED);
        verify(store, never()).findByAppRef(anyString());
    }

    @Test
    @DisplayName("wrong secret → REJECTED before the signature; gateway disabled → RETRY")
    void secretAndDisabled() {
        assertThat(webhooks.handle("other", Map.of())).isEqualTo(PaymentWebhookService.Result.REJECTED);
        verify(gateway, never()).verifyCallback(any());
        when(gateway.available()).thenReturn(false);
        assertThat(webhooks.handle(HASH, Map.of())).isEqualTo(PaymentWebhookService.Result.RETRY);
    }
}
