package com.keevo.messaging.notification.adapter.out;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.shared.capability.notification.NotificationPayload;
import com.keevo.shared.capability.notification.PushSendResult;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FCM push delivery channel (Story 8.0, 9-5; Story 19.9: a channel of the non-Expo tokens). */
@ExtendWith(MockitoExtension.class)
class FcmNotificationAdapterTest {

    private static final NotificationPayload PAYLOAD = NotificationPayload.of(
            "STOCK_ALERT", "Alerte stock", "Stock bas", "/stock/overview", Map.of("productId", "123"));

    @Mock
    private FirebaseMessaging firebaseMessaging;

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    private FcmNotificationAdapter adapter;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FirebaseMessaging> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(firebaseMessaging);
        adapter = new FcmNotificationAdapter(provider, deviceTokenRepository);
    }

    private static BatchResponse batch(int success, int failure, List<SendResponse> responses) {
        BatchResponse r = mock(BatchResponse.class);
        lenient().when(r.getSuccessCount()).thenReturn(success);
        lenient().when(r.getFailureCount()).thenReturn(failure);
        lenient().when(r.getResponses()).thenReturn(responses);
        return r;
    }

    private static SendResponse ok() {
        SendResponse r = mock(SendResponse.class);
        lenient().when(r.isSuccessful()).thenReturn(true);
        return r;
    }

    @Test
    void supportsOnlyNonExpoTokens() {
        assertTrue(adapter.supports("fcm-token"));
        assertFalse(adapter.supports("ExponentPushToken[abc]"));
        assertFalse(adapter.supports("ExpoPushToken[abc]"));
        assertFalse(adapter.supports(null));
    }

    @Test
    void send_multicastAndCounts() throws Exception {
        BatchResponse b = batch(2, 0, List.of(ok(), ok()));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(b);

        assertEquals(new PushSendResult(2, 0, 1, 0), adapter.send("kv_abc123", List.of("t1", "t2"), PAYLOAD));
        verify(firebaseMessaging).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    void send_chunksOf500_andSumsCounts() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 1001; i++) tokens.add("t-" + i);
        BatchResponse b1 = batch(500, 0, List.of());
        BatchResponse b2 = batch(499, 1, List.of());
        BatchResponse b3 = batch(1, 0, List.of());
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(b1, b2, b3);

        PushSendResult result = adapter.send("kv_abc123", tokens, PAYLOAD);

        verify(firebaseMessaging, times(3)).sendEachForMulticast(any(MulticastMessage.class));
        assertEquals(new PushSendResult(1000, 1, 3, 0), result);
    }

    @Test
    void send_unregisteredToken_deletedInTenantAndCountedAsFailure() throws Exception {
        SendResponse failResp = mock(SendResponse.class);
        when(failResp.isSuccessful()).thenReturn(false);
        FirebaseMessagingException fmEx = mock(FirebaseMessagingException.class);
        when(fmEx.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(failResp.getException()).thenReturn(fmEx);
        BatchResponse b = batch(1, 1, List.of(ok(), failResp));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(b);
        List<String> tenantDuringDelete = new ArrayList<>();
        when(deviceTokenRepository.deleteByToken("stale")).thenAnswer(inv -> {
            tenantDuringDelete.add(TenantContext.getCurrentTenant());
            return 1;
        });

        PushSendResult result = adapter.send("kv_abc123", List.of("good", "stale"), PAYLOAD);

        verify(deviceTokenRepository, never()).deleteByToken("good");
        assertEquals(new PushSendResult(1, 1, 1, 0), result);
        assertEquals(List.of("kv_abc123"), tenantDuringDelete);
        assertNull(TenantContext.getCurrentTenant());
    }

    @Test
    void send_chunkThrows_countedAsFailedChunk_neverThrows() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                .thenThrow(new RuntimeException("FCM down"));

        PushSendResult result = assertDoesNotThrow(() -> adapter.send("kv_abc123", List.of("a", "b"), PAYLOAD));

        assertEquals(new PushSendResult(0, 2, 1, 1), result);
        assertTrue(result.allChunksFailed());
    }

    @Test
    void send_empty_isNoOp() throws Exception {
        assertEquals(PushSendResult.EMPTY, adapter.send("kv_abc123", List.of(), PAYLOAD));
        verify(firebaseMessaging, never()).sendEachForMulticast(any());
        assertTrue(adapter.isAvailable());
    }

    @Test
    void firebaseAbsent_unavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FirebaseMessaging> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        FcmNotificationAdapter absent = new FcmNotificationAdapter(provider, deviceTokenRepository);
        assertFalse(absent.isAvailable());
        assertEquals(new PushSendResult(0, 1, 1, 1), absent.send("kv_abc123", List.of("a"), PAYLOAD));
    }
}
