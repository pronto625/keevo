package com.keevo.subscription.offer.application.service;

import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.subscription.offer.domain.model.UsagePack;
import com.keevo.subscription.offer.domain.port.out.OfferStore;
import com.keevo.subscription.offer.domain.port.out.UsagePackStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("UsagePackService — offered packs, unit price, journal wording (Story 21.3)")
class UsagePackServiceTest {

    private static UsagePack pack(String code, boolean active) {
        return new UsagePack(code, "Pack 100 SMS", UsageMetric.SMS, 100, 2000, active, 10, Instant.EPOCH);
    }

    @Test
    @DisplayName("only active packs are offered; an inactive or malformed code is not")
    void offered() {
        UsagePackStore store = mock(UsagePackStore.class);
        when(store.all()).thenReturn(List.of(pack("SMS_100", true), pack("SMS_250", false)));
        when(store.find("SMS_250")).thenReturn(Optional.of(pack("SMS_250", false)));
        UsagePackService service = new UsagePackService(store, mock(OfferStore.class));
        assertThat(service.offered()).extracting("code").containsExactly("SMS_100");
        assertThat(service.offered("sms_250")).isEmpty();
        assertThat(service.offered("bad code")).isEmpty();
    }

    @Test
    @DisplayName("unit price rounded half-up; journal summary in French")
    void unitPriceAndSummary() {
        assertThat(UsagePackService.unitPrice(2000, 100)).isEqualTo(20);
        assertThat(UsagePackService.unitPrice(1000, 3)).isEqualTo(333);
        assertThat(UsagePackService.unitPrice(5, 2)).isEqualTo(3);
        assertThat(UsagePackService.summary(null, pack("SMS_100", false)))
                .isEqualTo("Pack « Pack 100 SMS » : 100 SMS, 2 000 FCFA, non proposé (ajouté)");
    }
}
