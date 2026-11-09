package com.keevo.subscription.plan.application.service;

import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsagePackSource;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore.PackMovementRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PackHistoryService — labels and paging (Story 21.3)")
class PackHistoryServiceTest {

    private static final Instant AT = Instant.parse("2026-11-09T10:00:00Z");

    private static PackMovementRow row(UsagePackSource source, QuotaPeriod period, UUID actor, String name) {
        return new PackMovementRow(UUID.randomUUID(), UsageMetric.SMS, period, source == UsagePackSource.CONSUMPTION
                ? -1 : 100, 5, source, actor, name, AT);
    }

    @Test
    @DisplayName("counter: « Quota du mois épuisé » / « Quota à vie épuisé » for a consumption only; who: name, "
            + "« Envoi automatique » without actor, « Un membre de l'équipe » without name, none for an import")
    void labels() {
        UUID someone = UUID.randomUUID();
        assertThat(PackHistoryService.counterLabel(row(UsagePackSource.CONSUMPTION, QuotaPeriod.MONTH, null, null)))
                .isEqualTo("Quota du mois épuisé");
        assertThat(PackHistoryService.counterLabel(row(UsagePackSource.CONSUMPTION, QuotaPeriod.LIFETIME, null,
                null))).isEqualTo("Quota à vie épuisé");
        assertThat(PackHistoryService.counterLabel(row(UsagePackSource.PURCHASE, null, someone, null))).isNull();
        assertThat(PackHistoryService.byLabel(row(UsagePackSource.CONSUMPTION, QuotaPeriod.MONTH, someone,
                "Awa Ndiaye"))).isEqualTo("Awa Ndiaye");
        assertThat(PackHistoryService.byLabel(row(UsagePackSource.CONSUMPTION, QuotaPeriod.MONTH, null, null)))
                .isEqualTo("Envoi automatique");
        assertThat(PackHistoryService.byLabel(row(UsagePackSource.PURCHASE, null, someone, null)))
                .isEqualTo("Le propriétaire");
        assertThat(PackHistoryService.byLabel(row(UsagePackSource.CONSUMPTION, QuotaPeriod.MONTH, someone, null)))
                .isEqualTo("Un membre de l'équipe");
        assertThat(PackHistoryService.byLabel(row(UsagePackSource.MIGRATION, null, null, null))).isNull();
    }

    @Test
    @DisplayName("asks one row more than the page: next cursor only when there is more; limit 1..100")
    void paging() {
        UsageCounterStore store = mock(UsageCounterStore.class);
        PackHistoryService service = new PackHistoryService(store);
        when(store.packMovements(eq("kv_abc123"), any(), any(), any(), anyInt())).thenReturn(List.of(
                row(UsagePackSource.PURCHASE, null, null, null), row(UsagePackSource.PURCHASE, null, null, null)));
        assertThat(service.page("kv_abc123", null, null, 1).nextCursor()).isNotNull();
        verify(store).packMovements("kv_abc123", null, null, null, 2);
        assertThat(service.page("kv_abc123", "sms", null, 5).nextCursor()).isNull();
        assertThatThrownBy(() -> service.page("kv_abc123", null, null, 101)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.page("kv_abc123", null, null, 0)).isInstanceOf(DomainException.class);
    }
}
