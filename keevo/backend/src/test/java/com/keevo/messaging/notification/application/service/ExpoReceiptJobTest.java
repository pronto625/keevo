package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.model.ExpoPushTicket;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.ExpoPushTicketRepository;
import com.keevo.messaging.notification.domain.port.out.ExpoReceiptClient;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 19.9b — Expo receipts job: batches, one token delete per tenant, processed rows deleted, 24 h purge. */
@DisplayName("ExpoReceiptJob — receipts by batch, dead tokens per tenant (Story 19.9b)")
class ExpoReceiptJobTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final String T1 = "kv_aaa111";
    private static final String T2 = "kv_bbb222";

    /** In-memory ticket table with the repository's keyset semantics. */
    static final class MemoryTickets implements ExpoPushTicketRepository {
        final List<ExpoPushTicket> rows = new ArrayList<>();
        final List<Collection<String>> deletes = new ArrayList<>();
        int selects;

        @Override
        public void saveAll(String tenantSchema, Map<String, String> tokenByTicketId) {
            tokenByTicketId.forEach((id, token) -> rows.add(new ExpoPushTicket(id, token, tenantSchema, NOW)));
        }

        @Override
        public List<ExpoPushTicket> findCreatedBefore(Instant before, Instant afterCreatedAt, String afterTicketId,
                                                      int limit) {
            selects++;
            Comparator<ExpoPushTicket> order = Comparator.comparing(ExpoPushTicket::createdAt)
                    .thenComparing(ExpoPushTicket::ticketId);
            return rows.stream()
                    .filter(t -> t.createdAt().isBefore(before))
                    .filter(t -> afterCreatedAt == null || t.createdAt().isAfter(afterCreatedAt)
                            || (t.createdAt().equals(afterCreatedAt) && t.ticketId().compareTo(afterTicketId) > 0))
                    .sorted(order).limit(limit).toList();
        }

        @Override
        public int deleteByIds(Collection<String> ticketIds) {
            deletes.add(List.copyOf(ticketIds));
            int before = rows.size();
            rows.removeIf(t -> ticketIds.contains(t.ticketId()));
            return before - rows.size();
        }

        @Override
        public int purgeCreatedBefore(Instant before) {
            int size = rows.size();
            rows.removeIf(t -> t.createdAt().isBefore(before));
            return size - rows.size();
        }
    }

    private MemoryTickets tickets;
    private ExpoReceiptClient receipts;
    private DeviceTokenRepository deviceTokens;
    private ExpoReceiptJob job;
    private final List<String> tenantsSeen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tickets = new MemoryTickets();
        receipts = mock(ExpoReceiptClient.class);
        deviceTokens = mock(DeviceTokenRepository.class);
        when(deviceTokens.deleteByTokens(any())).thenAnswer(inv -> {
            tenantsSeen.add(TenantContext.getCurrentTenant());
            return ((Collection<?>) inv.getArgument(0)).size();
        });
        job = new ExpoReceiptJob(tickets, receipts, deviceTokens, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void ticket(String id, String token, String tenant, Duration age) {
        tickets.rows.add(new ExpoPushTicket(id, token, tenant, NOW.minus(age)));
    }

    @Test
    @DisplayName("DeviceNotRegistered on 3 tickets of 2 tenants: one delete per tenant, processed rows deleted")
    void deadTokensPerTenant() {
        ticket("a", "ExponentPushToken[a]", T1, Duration.ofMinutes(20));
        ticket("b", "ExponentPushToken[b]", T1, Duration.ofMinutes(20));
        ticket("c", "ExponentPushToken[c]", T2, Duration.ofMinutes(30));
        ticket("d", "ExponentPushToken[d]", T2, Duration.ofMinutes(40));
        ticket("young", "ExponentPushToken[y]", T1, Duration.ofMinutes(5));
        when(receipts.fetchReceipts(any())).thenReturn(Map.of(
                "a", "DeviceNotRegistered", "b", "DeviceNotRegistered", "c", "DeviceNotRegistered",
                "d", "MessageRateExceeded"));

        job.run();

        verify(deviceTokens).deleteByTokens(List.of("ExponentPushToken[c]"));
        verify(deviceTokens).deleteByTokens(List.of("ExponentPushToken[a]", "ExponentPushToken[b]"));
        assertThat(tenantsSeen).containsExactlyInAnyOrder(T1, T2);
        assertThat(tickets.deletes).hasSize(1);
        assertThat(tickets.deletes.get(0)).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(tickets.rows).extracting(ExpoPushTicket::ticketId).containsExactly("young");
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("tickets older than 24 h are deleted without a receipt call")
    void purgeOld() {
        ticket("old", "ExponentPushToken[o]", T1, Duration.ofHours(25));

        job.run();

        verify(receipts, never()).fetchReceipts(any());
        assertThat(tickets.rows).isEmpty();
    }

    @Test
    @DisplayName("batches of 1000 with a keyset cursor; tickets without a receipt yet stay")
    void batches() {
        IntStream.range(0, 1500).forEach(i -> ticket(String.format("t%04d", i), "ExponentPushToken[" + i + "]", T1,
                Duration.ofMinutes(20)));
        List<Integer> sizes = new ArrayList<>();
        when(receipts.fetchReceipts(any())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            sizes.add(ids.size());
            Map<String, String> ok = new HashMap<>();
            ids.stream().filter(id -> !id.equals("t0000")).forEach(id -> ok.put(id, ""));
            return ok;
        });

        job.run();

        assertThat(sizes).containsExactly(1000, 500);
        verify(deviceTokens, never()).deleteByTokens(any());
        assertThat(tickets.rows).extracting(ExpoPushTicket::ticketId).containsExactly("t0000");
    }

    @Test
    @DisplayName("a failed receipt call stops the run; tickets kept for the next one")
    void receiptCallFails() {
        ticket("a", "ExponentPushToken[a]", T1, Duration.ofMinutes(20));
        when(receipts.fetchReceipts(any())).thenThrow(new IllegalStateException("expo down"));

        job.run();

        assertThat(tickets.rows).hasSize(1);
        assertThat(tickets.deletes).isEmpty();
    }

    @Test
    @DisplayName("an invalid tenant schema is never set in the context")
    void invalidSchema() {
        ticket("a", "ExponentPushToken[a]", "public", Duration.ofMinutes(20));
        when(receipts.fetchReceipts(any())).thenReturn(Map.of("a", "DeviceNotRegistered"));

        job.run();

        verify(deviceTokens, never()).deleteByTokens(any());
        assertThat(tickets.rows).isEmpty();
    }
}
