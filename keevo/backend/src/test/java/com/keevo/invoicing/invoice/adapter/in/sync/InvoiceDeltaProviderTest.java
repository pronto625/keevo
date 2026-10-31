package com.keevo.invoicing.invoice.adapter.in.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.keevo.invoicing.invoice.application.usecase.InvoiceQueryUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicingSettingsUseCase;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner.SyncKeysetIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("InvoiceDeltaProvider — invoices of the sync pull (Story 15.5b)")
class InvoiceDeltaProviderTest {

    private static final Instant SINCE = Instant.parse("2026-10-01T00:00:00Z");

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final InvoiceQueryUseCase queries = mock(InvoiceQueryUseCase.class);
    private final InvoicingSettingsUseCase settings = mock(InvoicingSettingsUseCase.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final com.keevo.shared.module.ModuleProvisioner provisioner =
            mock(com.keevo.shared.module.ModuleProvisioner.class);
    private InvoiceDeltaProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InvoiceDeltaProvider(invoices, queries, settings, objectMapper, provisioner);
        when(invoices.tablesExist()).thenReturn(true);
        when(settings.current()).thenReturn(InvoicingSettings.defaults());
        when(queries.credits(any())).thenReturn(Map.of());
    }

    private static Invoice draft(Instant updatedAt) {
        return Invoice.draft(UUID.randomUUID(), new ClientInfo(null, "Société Ndolo", null, null, "+237690000001"),
                List.of(new LineInput("Riz", BigDecimal.ONE, 1000, VatTreatment.STANDARD)), InvoicingSettings.defaults(),
                UUID.randomUUID(), updatedAt);
    }

    private static InvoiceRepository.DeltaItem live(Invoice i) {
        return new InvoiceRepository.DeltaItem(i, false);
    }

    @Test
    @DisplayName("a deleted draft → tombstone {id, deleted, updatedAt}, no credits / settings read for it")
    void tombstone() {
        Invoice gone = draft(Instant.parse("2026-10-30T10:00:00Z"));
        Invoice kept = draft(Instant.parse("2026-10-30T11:00:00Z"));
        when(invoices.delta(eq(SINCE), isNull(), isNull(), eq(51)))
                .thenReturn(List.of(new InvoiceRepository.DeltaItem(gone, true), live(kept)));

        DeltaEntityProvider.Page page = provider.queryDeltaPage(SINCE, null, 50);

        assertThat(page.rows().get(0)).containsExactly(Map.entry("id", gone.id().toString()),
                Map.entry("deleted", true), Map.entry("updatedAt", "2026-10-30T10:00:00Z"));
        assertThat(page.rows().get(1).get("status")).isEqualTo("DRAFT");
        verify(queries).credits(List.of(kept));
    }

    @Test
    @DisplayName("key `invoices`, owned by invoicing (skipped by the pull while DISABLED), every role")
    void contract() {
        assertThat(provider.entityKey()).isEqualTo("invoices");
        assertThat(provider.moduleId()).isEqualTo("invoicing");
        assertThat(provider.ownerOnly()).isFalse();
    }

    @Test
    @DisplayName("its cursor leads the invoices entry of SYNC_KEYSET_INDEXES (module table, created by V6)")
    void cursorMatchesTheKeysetIndex() {
        SyncKeysetIndex index = TenantSchemaProvisioner.SYNC_KEYSET_INDEXES.stream()
                .filter(i -> i.table().equals("invoices")).findFirst().orElseThrow();
        assertThat(index.cursor()).isEqualTo(InvoiceDeltaProvider.CURSOR).isEqualTo("updated_at");
        assertThat(index.moduleId()).isEqualTo("invoicing");
        assertThat(index.name()).isEqualTo("idx_invoices_sync_keyset");
    }

    @Test
    @DisplayName("rows are the REST DTO as a map (status label, client, lines, ISO dates); last page has no next")
    void rowsShapedLikeTheApi() {
        Invoice i = draft(Instant.parse("2026-10-30T10:00:00Z"));
        when(invoices.delta(eq(SINCE), isNull(), isNull(), eq(51))).thenReturn(List.of(live(i)));

        DeltaEntityProvider.Page page = provider.queryDeltaPage(SINCE, null, 50);

        assertThat(page.hasMore()).isFalse();
        Map<String, Object> row = page.rows().get(0);
        assertThat(row.get("id")).isEqualTo(i.id().toString());
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("statusLabel")).isEqualTo("Brouillon");
        assertThat(row.get("updatedAt")).isEqualTo("2026-10-30T10:00:00Z");
        assertThat(((Number) row.get("totalTtc")).longValue()).isEqualTo(1000L);
        assertThat(((Map<?, ?>) row.get("client")).get("name")).isEqualTo("Société Ndolo");
        assertThat((List<?>) row.get("lines")).hasSize(1);
    }

    @Test
    @DisplayName("one extra row read: more → next position = (updatedAt, id) of the last row sent")
    void paged() {
        Invoice a = draft(Instant.parse("2026-10-30T10:00:00Z"));
        Invoice b = draft(Instant.parse("2026-10-30T11:00:00Z"));
        Invoice c = draft(Instant.parse("2026-10-30T12:00:00Z"));
        when(invoices.delta(eq(SINCE), isNull(), isNull(), eq(3))).thenReturn(List.of(live(a), live(b), live(c)));

        DeltaEntityProvider.Page page = provider.queryDeltaPage(SINCE, null, 2);

        assertThat(page.rows()).hasSize(2);
        assertThat(page.next()).isEqualTo(new DeltaEntityProvider.Position(b.updatedAt(), b.id().toString()));

        DeltaEntityProvider.Position after = page.next();
        when(invoices.delta(SINCE, after.cursor(), after.id(), 3)).thenReturn(List.of(live(c)));
        DeltaEntityProvider.Page second = provider.queryDeltaPage(SINCE, after, 2);
        assertThat(second.rows()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    @DisplayName("tables never provisioned for the tenant: empty, done, nothing queried")
    void notProvisioned() {
        when(invoices.tablesExist()).thenReturn(false);
        DeltaEntityProvider.Page page = provider.queryDeltaPage(SINCE, null, 50);
        assertThat(page.rows()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        verify(invoices, never()).delta(any(), any(), any(), anyInt());
        verify(provisioner, never()).ensureProvisioned(any(), any());
    }

    @Test
    @DisplayName("tables there: brought to the module's latest version before being read")
    void upgradedBeforeRead() {
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test01");
        try {
            when(invoices.delta(any(), any(), any(), anyInt())).thenReturn(List.of());
            provider.queryDeltaPage(SINCE, null, 50);
            var order = org.mockito.Mockito.inOrder(provisioner, invoices);
            order.verify(provisioner).ensureProvisioned("kv_test01", "invoicing");
            order.verify(invoices).delta(any(), any(), any(), anyInt());
        } finally {
            com.keevo.shared.infrastructure.persistence.TenantContext.clear();
        }
    }
}
