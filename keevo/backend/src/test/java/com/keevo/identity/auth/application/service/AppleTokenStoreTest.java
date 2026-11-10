package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient.AppleGrant;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AppleTokenStore (Story 19.3b)")
class AppleTokenStoreTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final UUID ROW = UUID.randomUUID();

    @Mock AppleTokenClient client;
    @Mock UserAuthenticatorRepository authenticators;
    @Mock com.keevo.identity.auth.domain.port.out.AppleRevocationQueue queue;

    private final SecretCipher cipher = new SecretCipher(KEY);
    private AppleTokenStore store;

    private static final SocialIdentity APPLE =
            new SocialIdentity(SocialProvider.APPLE, "a-sub", null, false, null, null, "com.keevo.app", null, null);

    @BeforeEach
    void setUp() {
        store = new AppleTokenStore(client, authenticators, cipher, queue);
        when(authenticators.storeRevocationToken(any(), any(), any())).thenReturn(true);
        when(client.enabled()).thenReturn(true);
        when(authenticators.findId(SocialProvider.APPLE, "a-sub")).thenReturn(Optional.of(ROW));
    }

    @Test
    @DisplayName("code exchanged with the token's audience, refresh token stored encrypted (row id context)")
    void stores() {
        when(client.exchange("com.keevo.app", "code")).thenReturn(Optional.of(new AppleGrant("rt-1", "a-sub")));

        store.storeIfPossible(APPLE, " code ");

        ArgumentCaptor<byte[]> blob = ArgumentCaptor.forClass(byte[].class);
        verify(authenticators).storeRevocationToken(org.mockito.ArgumentMatchers.eq(ROW),
                org.mockito.ArgumentMatchers.eq("com.keevo.app"), blob.capture());
        assertThat(new String(blob.getValue())).doesNotContain("rt-1");
        assertThat(cipher.decrypt(blob.getValue(), ROW.toString())).isEqualTo("rt-1");
    }

    @Test
    @DisplayName("grant of another Apple user, refused code, or a failure → nothing stored, no exception")
    void nothingStored() {
        when(client.exchange(anyString(), anyString())).thenReturn(Optional.of(new AppleGrant("rt", "other")));
        store.storeIfPossible(APPLE, "c1");
        when(client.exchange(anyString(), anyString())).thenReturn(Optional.empty());
        store.storeIfPossible(APPLE, "c2");
        when(client.exchange(anyString(), anyString())).thenThrow(new IllegalStateException("down"));
        store.storeIfPossible(APPLE, "c3");

        verify(authenticators, never()).storeRevocationToken(any(), any(), any());
    }

    @Test
    @DisplayName("disabled adapter, no code, Google, or no cipher key → no exchange")
    void noExchange() {
        store.storeIfPossible(APPLE, null);
        store.storeIfPossible(APPLE, " ");
        store.storeIfPossible(new SocialIdentity(SocialProvider.GOOGLE, "g", null, false, null, null, "c", null, null),
                "code");
        new AppleTokenStore(client, authenticators, new SecretCipher(""), queue).storeIfPossible(APPLE, "code");
        when(client.enabled()).thenReturn(false);
        store.storeIfPossible(APPLE, "code");

        verify(client, never()).exchange(any(), any());
    }

    @Test
    @DisplayName("disabled adapter → not even the repository is touched")
    void disabledTouchesNothing() {
        when(client.enabled()).thenReturn(false);
        store.storeIfPossible(APPLE, "code");
        verifyNoInteractions(authenticators);
    }

    @Test
    @DisplayName("link removed during the exchange (row gone, or UPDATE on 0 rows) → token queued for revocation")
    void rowGoneQueuesRevocation() {
        when(client.exchange("com.keevo.app", "code")).thenReturn(Optional.of(new AppleGrant("rt-9", "a-sub")));
        when(authenticators.findId(SocialProvider.APPLE, "a-sub")).thenReturn(Optional.empty());
        store.storeIfPossible(APPLE, "code");

        when(authenticators.findId(SocialProvider.APPLE, "a-sub")).thenReturn(Optional.of(ROW));
        when(authenticators.storeRevocationToken(any(), any(), any())).thenReturn(false);
        store.storeIfPossible(APPLE, "code");

        ArgumentCaptor<UUID> context = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> blob = ArgumentCaptor.forClass(byte[].class);
        verify(queue, org.mockito.Mockito.times(2)).enqueue(context.capture(),
                org.mockito.ArgumentMatchers.eq("com.keevo.app"), blob.capture());
        for (int i = 0; i < 2; i++) {
            assertThat(cipher.decrypt(blob.getAllValues().get(i), context.getAllValues().get(i).toString()))
                    .isEqualTo("rt-9");
        }
    }
}
