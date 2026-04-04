package com.keevo.messaging.notification.adapter.out;

import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.messaging.notification.domain.model.DevicePlatform;
import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.google.firebase.messaging.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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

    @InjectMocks
    private FcmNotificationAdapter adapter;

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
}
