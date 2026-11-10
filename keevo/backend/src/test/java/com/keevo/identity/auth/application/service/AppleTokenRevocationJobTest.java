package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.port.out.AppleRevocationQueue;
import com.keevo.identity.auth.domain.port.out.AppleRevocationQueue.PendingRevocation;
import com.keevo.identity.auth.domain.port.out.AppleTokenClient;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AppleTokenRevocationJob (Story 19.3b)")
class AppleTokenRevocationJobTest {

    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Mock AppleRevocationQueue queue;
    @Mock AppleTokenClient client;

    private AppleTokenRevocationJob job;

    @BeforeEach
    void setUp() {
        job = new AppleTokenRevocationJob(queue, client, cipher);
        when(client.enabled()).thenReturn(true);
    }

    private PendingRevocation pending(String token) {
        UUID authenticator = UUID.randomUUID();
        return new PendingRevocation(UUID.randomUUID(), authenticator, "com.keevo.app",
                cipher.encrypt(token, authenticator.toString()), 0);
    }

    @Test
    @DisplayName("revoked and failed rows: one delete, one backoff — set-based")
    void outcomes() {
        PendingRevocation ok = pending("rt-ok");
        PendingRevocation ko = pending("rt-ko");
        when(queue.findDue(AppleTokenRevocationJob.BATCH)).thenReturn(List.of(ok, ko));
        doThrow(new IllegalStateException("503")).when(client).revoke("com.keevo.app", "rt-ko");

        job.run();

        verify(client).revoke("com.keevo.app", "rt-ok");
        verify(queue).delete(List.of(ok.id()));
        verify(queue).recordFailures(List.of(ko.id()), AppleTokenRevocationJob.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("a token that cannot be decrypted (other context / rotated key) is dropped, never sent")
    void undecryptable() {
        PendingRevocation good = pending("rt");
        PendingRevocation bad = new PendingRevocation(UUID.randomUUID(), UUID.randomUUID(), "com.keevo.app",
                good.token(), 0);
        when(queue.findDue(anyInt())).thenReturn(List.of(bad));

        job.run();

        verify(client, never()).revoke(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(queue).delete(List.of(bad.id()));
        verify(queue).recordFailures(List.of(), AppleTokenRevocationJob.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("adapter disabled → queue never read (rows wait)")
    void disabled() {
        when(client.enabled()).thenReturn(false);
        job.run();
        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("nothing due → no write")
    void nothingDue() {
        when(queue.findDue(anyInt())).thenReturn(List.of());
        job.run();
        verify(queue, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
