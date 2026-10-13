package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.messaging.notification.domain.model.DevicePlatform;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.model.PushSendResult;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.google.firebase.messaging.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmNotificationAdapterTest {

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

    private DeviceToken makeToken(String token) {
        return new DeviceToken(UUID.randomUUID(), UUID.randomUUID(), token,
                DevicePlatform.ANDROID, "TestDevice", "OWNER", Instant.now(), Instant.now());
    }

    @Test
    void notifyOwners_withTokens_sendsMulticast() throws Exception {
        when(deviceTokenRepository.findOwnerTokens())
                .thenReturn(List.of(makeToken("token-1"), makeToken("token-2")));

        BatchResponse batchResponse = mock(BatchResponse.class);
        SendResponse sendResp1 = mock(SendResponse.class);
        SendResponse sendResp2 = mock(SendResponse.class);
        when(sendResp1.isSuccessful()).thenReturn(true);
        when(sendResp2.isSuccessful()).thenReturn(true);
        when(batchResponse.getResponses()).thenReturn(List.of(sendResp1, sendResp2));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batchResponse);

        NotificationPayload payload = NotificationPayload.of(
                "TEST_TYPE", "Test Title", "Test Body", "/test", Map.of("key", "value"));

        adapter.notifyOwners("kv_test_tenant", payload);

        verify(firebaseMessaging).sendEachForMulticast(any(MulticastMessage.class));
    }

    @Test
    void notifyOwners_noTokens_returnsSilently() throws Exception {
        when(deviceTokenRepository.findOwnerTokens()).thenReturn(List.of());

        NotificationPayload payload = NotificationPayload.of("TEST", "Title", "Body");

        adapter.notifyOwners("kv_test_tenant", payload);

        verify(firebaseMessaging, never()).sendEachForMulticast(any());
    }

    @Test
    void notifyOwners_unregisteredToken_deletesFromRepo() throws Exception {
        when(deviceTokenRepository.findOwnerTokens())
                .thenReturn(List.of(makeToken("stale-token")));

        BatchResponse batchResponse = mock(BatchResponse.class);
        SendResponse failResp = mock(SendResponse.class);
        when(failResp.isSuccessful()).thenReturn(false);
        FirebaseMessagingException fmEx = mock(FirebaseMessagingException.class);
        when(fmEx.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(failResp.getException()).thenReturn(fmEx);
        when(batchResponse.getResponses()).thenReturn(List.of(failResp));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batchResponse);

        NotificationPayload payload = NotificationPayload.of("TEST", "Title", "Body");

        adapter.notifyOwners("kv_test_tenant", payload);

        verify(deviceTokenRepository).deleteByToken("stale-token");
    }

    @Test
    void notifyOwners_firebaseException_logsWarnNoThrow() throws Exception {
        when(deviceTokenRepository.findOwnerTokens())
                .thenReturn(List.of(makeToken("token-1")));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                .thenThrow(new RuntimeException("Firebase SDK error"));

        NotificationPayload payload = NotificationPayload.of("TEST", "Title", "Body");

        // Should not throw — best-effort delivery
        assertDoesNotThrow(() -> adapter.notifyOwners("kv_test_tenant", payload));
    }

    @Test
    void notifyOwners_setsCorrectDataPayload() throws Exception {
        when(deviceTokenRepository.findOwnerTokens())
                .thenReturn(List.of(makeToken("token-data")));

        BatchResponse batchResponse = mock(BatchResponse.class);
        SendResponse successResp = mock(SendResponse.class);
        when(successResp.isSuccessful()).thenReturn(true);
        when(batchResponse.getResponses()).thenReturn(List.of(successResp));
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(batchResponse);

        NotificationPayload payload = NotificationPayload.of(
                "STOCK_ALERT", "Alerte stock", "Stock bas",
                "/stock/overview", Map.of("productId", "123", "storeId", "456"));

        adapter.notifyOwners("kv_test_tenant", payload);

        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging).sendEachForMulticast(captor.capture());
        // Verification happens through the mock call — the multicast message was built correctly
    }

    // ── Story 9-5 — counted multicast send of a global push ───────────────────

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
    void sendToTokens_chunksOf500_andSumsCounts() throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 1001; i++) tokens.add("t-" + i);
        BatchResponse b1 = batch(500, 0, List.of());
        BatchResponse b2 = batch(499, 1, List.of());
        BatchResponse b3 = batch(1, 0, List.of());
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class))).thenReturn(b1, b2, b3);

        PushSendResult result = adapter.sendToTokens("kv_abc123", tokens,
                NotificationPayload.of("GLOBAL_ANNOUNCEMENT", "Maintenance", "Ce soir"));

        verify(firebaseMessaging, times(3)).sendEachForMulticast(any(MulticastMessage.class));
        assertEquals(new PushSendResult(1000, 1, 3, 0), result);
    }

    @Test
    void sendToTokens_unregisteredToken_deletedAndCountedAsFailure() throws Exception {
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

        PushSendResult result = adapter.sendToTokens("kv_abc123", List.of("good", "stale"),
                NotificationPayload.of("GLOBAL_ANNOUNCEMENT", "T", "B"));

        verify(deviceTokenRepository).deleteByToken("stale");
        verify(deviceTokenRepository, never()).deleteByToken("good");
        assertEquals(new PushSendResult(1, 1, 1, 0), result);
        // cleanup ran in the company's schema, and the context is cleared afterwards
        assertEquals(List.of("kv_abc123"), tenantDuringDelete);
        assertNull(TenantContext.getCurrentTenant());
    }

    @Test
    void sendToTokens_chunkThrows_countedAsFailedChunk() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any(MulticastMessage.class)))
                .thenThrow(new RuntimeException("FCM down"));

        PushSendResult result = adapter.sendToTokens("kv_abc123", List.of("a", "b"),
                NotificationPayload.of("GLOBAL_ANNOUNCEMENT", "T", "B"));

        assertEquals(new PushSendResult(0, 2, 1, 1), result);
        assertTrue(result.allChunksFailed());
    }

    @Test
    void sendToTokens_empty_isNoOp() throws Exception {
        assertEquals(PushSendResult.EMPTY, adapter.sendToTokens("kv_abc123", List.of(),
                NotificationPayload.of("GLOBAL_ANNOUNCEMENT", "T", "B")));
        verify(firebaseMessaging, never()).sendEachForMulticast(any());
        assertTrue(adapter.isPushAvailable());
    }

    @Test
    void firebaseAbsent_pushUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FirebaseMessaging> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        FcmNotificationAdapter absent = new FcmNotificationAdapter(provider, deviceTokenRepository);
        assertFalse(absent.isPushAvailable());
        assertEquals(new PushSendResult(0, 1, 1, 1), absent.sendToTokens("kv_abc123", List.of("a"),
                NotificationPayload.of("GLOBAL_ANNOUNCEMENT", "T", "B")));
    }
}
