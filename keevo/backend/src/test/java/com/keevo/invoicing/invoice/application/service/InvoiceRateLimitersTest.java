package com.keevo.invoicing.invoice.application.service;

import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Invoice rate limiters (Story 14.2)")
class InvoiceRateLimitersTest {

    @Test
    @DisplayName("sendings: a released slot (provider failure) does not consume the hourly quota")
    void sendRelease() {
        InvoiceSendRateLimiter limiter = new InvoiceSendRateLimiter(2, Clock.systemUTC());
        limiter.acquire("kv_a");
        limiter.acquire("kv_a");
        limiter.release("kv_a");
        limiter.acquire("kv_a");
        assertThatThrownBy(() -> limiter.acquire("kv_a")).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode()).isEqualTo("RATE_LIMIT_EXCEEDED"));
        limiter.acquire("kv_b");
    }

    @Test
    @DisplayName("public link: 30 openings per token per 10 minutes, then INVOICE_LINK_RATE_LIMITED")
    void linkLimit() {
        InvoiceLinkRateLimiter limiter = new InvoiceLinkRateLimiter(Clock.systemUTC());
        for (int i = 0; i < InvoiceLinkRateLimiter.MAX_PER_WINDOW; i++) {
            limiter.acquire("token-a");
        }
        assertThatThrownBy(() -> limiter.acquire("token-a"))
                .satisfies(e -> assertThat(((DomainException) e).getDomainCode())
                        .isEqualTo("INVOICE_LINK_RATE_LIMITED"));
        limiter.acquire("token-b");
    }
}
