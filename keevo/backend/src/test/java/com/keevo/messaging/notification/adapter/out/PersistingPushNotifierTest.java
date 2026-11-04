package com.keevo.messaging.notification.adapter.out;

import com.keevo.identity.api.UserQueryPort;
import com.keevo.messaging.notification.domain.model.DevicePlatform;
import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.messaging.notification.domain.model.PushTokens;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.messaging.notification.domain.port.out.NotificationInboxRepository;
import com.keevo.messaging.notification.domain.port.out.PushDeliveryChannel;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PersistingPushNotifier — inbox decorator and push composite (Story 19.9)")
class PersistingPushNotifierTest {

    private static final String TENANT = "kv_abc123";
    private static final NotificationPayload PAYLOAD = NotificationPayload.of("STOCK_ALERT", "Stock bas", "Riz");

    private NotificationInboxRepository inbox;
    private UserQueryPort users;
    private DeviceTokenRepository tokens;
    private final List<String> txEvents = new ArrayList<>();
    private PlatformTransactionManager tx;

    /** A channel stub: records its calls and, like the real adapters, clears the TenantContext. */
    private static final class StubChannel implements PushDeliveryChannel {
        final Predicate<String> kind;
        final boolean available;
        final List<List<String>> calls = new ArrayList<>();
        final List<String> tenantsSeen = new ArrayList<>();
        RuntimeException failure;

        StubChannel(Predicate<String> kind, boolean available) {
            this.kind = kind;
            this.available = available;
        }

        @Override
        public boolean supports(String token) {
            return kind.test(token);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public PushSendResult send(String tenantSchema, List<String> tokens, NotificationPayload payload) {
            calls.add(tokens);
            tenantsSeen.add(tenantSchema);
            TenantContext.clear();
            if (failure != null) throw failure;
            return new PushSendResult(tokens.size(), 0, 1, 0);
        }
    }

    private final StubChannel expo = new StubChannel(PushTokens::isExpo, true);
    private final StubChannel fcm = new StubChannel(t -> !PushTokens.isExpo(t), true);

    @BeforeEach
    void setUp() {
        inbox = mock(NotificationInboxRepository.class);
        users = mock(UserQueryPort.class);
        tokens = mock(DeviceTokenRepository.class);
        tx = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                txEvents.add("begin");
                return new SimpleTransactionStatus(true);
            }

            @Override
            public void commit(TransactionStatus status) {
                txEvents.add("commit");
            }

            @Override
            public void rollback(TransactionStatus status) {
                txEvents.add("rollback");
            }
        };
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private PersistingPushNotifier notifier(PushDeliveryChannel... channels) {
        return new PersistingPushNotifier(inbox, users, tokens, List.of(channels), tx);
    }

    private static DeviceToken token(String value) {
        return new DeviceToken(UUID.randomUUID(), UUID.randomUUID(), value, DevicePlatform.ANDROID, null, "OWNER",
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("notifyOwners: owners resolved and persisted in one short transaction, tokens read once, each "
            + "channel gets its own kind")
    void ownersPersistedThenRouted() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(users.findOwnerUserIds(TENANT)).thenAnswer(inv -> {
            assertThat(txEvents).containsExactly("begin"); // the lookup runs inside the REQUIRES_NEW callback
            return List.of(a, b, a);
        });
        List<String> tenantDuringQuery = new ArrayList<>();
        when(tokens.findOwnerTokens()).thenAnswer(inv -> {
            tenantDuringQuery.add(TenantContext.getCurrentTenant());
            return List.of(token("fcm-1"), token("ExponentPushToken[a]"), token("fcm-2"), token("fcm-1"));
        });

        notifier(expo, fcm).notifyOwners(TENANT, PAYLOAD);

        InOrder order = inOrder(inbox, tokens);
        order.verify(inbox).insertAll(TENANT, List.of(a, b), PAYLOAD);
        order.verify(tokens, times(1)).findOwnerTokens();
        assertThat(txEvents).containsExactly("begin", "commit");
        assertThat(tenantDuringQuery).containsExactly(TENANT);
        assertThat(expo.calls).containsExactly(List.of("ExponentPushToken[a]"));
        assertThat(fcm.calls).containsExactly(List.of("fcm-1", "fcm-2"));
    }

    @Test
    @DisplayName("notifyUsers: recipients de-duplicated for the inbox and the token query")
    void usersDeduplicated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(tokens.findByUserIds(List.of(a, b))).thenReturn(List.of(token("fcm-1")));

        notifier(expo, fcm).notifyUsers(TENANT, List.of(a, b, a), PAYLOAD);

        verify(inbox).insertAll(TENANT, List.of(a, b), PAYLOAD);
        verify(tokens).findByUserIds(List.of(a, b));
        verifyNoInteractions(users);
        assertThat(fcm.calls).containsExactly(List.of("fcm-1"));
        assertThat(expo.calls).isEmpty();
    }

    @Test
    @DisplayName("a persistence failure never prevents the push")
    void persistenceFailureStillPushes() {
        when(users.findOwnerUserIds(TENANT)).thenReturn(List.of(UUID.randomUUID()));
        doThrow(new IllegalStateException("db down")).when(inbox).insertAll(anyString(), any(), any());
        when(tokens.findOwnerTokens()).thenReturn(List.of(token("fcm-1")));

        notifier(fcm).notifyOwners(TENANT, PAYLOAD);

        assertThat(txEvents).containsExactly("begin", "rollback");
        assertThat(fcm.calls).hasSize(1);
    }

    @Test
    @DisplayName("an owner lookup failure stays inside the short transaction; the push still goes")
    void ownerLookupFailure() {
        when(users.findOwnerUserIds(TENANT)).thenThrow(new IllegalStateException("db down"));
        when(tokens.findOwnerTokens()).thenReturn(List.of(token("fcm-1")));

        notifier(fcm).notifyOwners(TENANT, PAYLOAD);

        assertThat(txEvents).containsExactly("begin", "rollback");
        assertThat(fcm.calls).hasSize(1);
    }

    @Test
    @DisplayName("a failing channel does not stop the next one; the caller's tenant is restored after notifyOwners")
    void channelsIsolatedAndContextRestored() {
        expo.failure = new RuntimeException("x");
        when(users.findOwnerUserIds(TENANT)).thenReturn(List.of());
        when(tokens.findOwnerTokens()).thenReturn(List.of(token("ExponentPushToken[a]"), token("fcm-1")));
        TenantContext.setCurrentTenant("kv_zzz999");

        notifier(expo, fcm).notifyOwners(TENANT, PAYLOAD);

        assertThat(fcm.calls).hasSize(1);
        assertThat(fcm.tenantsSeen).containsExactly(TENANT);
        verify(inbox, never()).insertAll(anyString(), any(), any());
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("kv_zzz999");
    }

    @Test
    @DisplayName("sendToTokens: not persisted; routed by kind, results summed, caller's tenant restored")
    void sendToTokensRouted() {
        TenantContext.setCurrentTenant("kv_zzz999");

        PushSendResult result = notifier(expo, fcm).sendToTokens(TENANT,
                List.of("fcm-1", "ExponentPushToken[a]", "fcm-2"), PAYLOAD);

        assertThat(result).isEqualTo(new PushSendResult(3, 0, 2, 0));
        assertThat(expo.calls).containsExactly(List.of("ExponentPushToken[a]"));
        assertThat(fcm.calls).containsExactly(List.of("fcm-1", "fcm-2"));
        verifyNoInteractions(inbox, users, tokens);
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("kv_zzz999");
    }

    @Test
    @DisplayName("sendToTokens: tokens no channel supports are failures, never sent")
    void unsupportedTokens() {
        PushSendResult result = notifier(fcm).sendToTokens(TENANT, List.of("ExponentPushToken[a]"), PAYLOAD);

        assertThat(result).isEqualTo(new PushSendResult(0, 1, 0, 0));
        assertThat(fcm.calls).isEmpty();
    }

    @Test
    @DisplayName("isPushAvailable: true when any channel is available")
    void available() {
        StubChannel stub = new StubChannel(t -> true, false);

        assertThat(notifier(stub).isPushAvailable()).isFalse();
        assertThat(notifier(stub, fcm).isPushAvailable()).isTrue();
    }
}
