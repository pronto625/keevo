package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.port.out.SocialNonceRepository;
import com.keevo.shared.MutableTestClock;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SocialNonceChecker (Story 19.3b)")
class SocialNonceCheckerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final String CLEAR = "r4nd0m-n0nce-0123456789";

    /** In-memory one-time store, like the primary key of social_nonces. */
    static final class MemoryNonces implements SocialNonceRepository {
        final Set<String> used = new HashSet<>();
        Instant lastExpiry;

        @Override
        public boolean consume(SocialProvider provider, String nonce, Instant expiresAt) {
            lastExpiry = expiresAt;
            return used.add(provider + ":" + nonce);
        }

        @Override
        public int purgeExpiredBefore(Instant cutoff) {
            return 0;
        }
    }

    private final MemoryNonces nonces = new MemoryNonces();
    private final MutableTestClock clock = new MutableTestClock(NOW);

    private static SocialIdentity identity(String nonceClaim) {
        return new SocialIdentity(SocialProvider.GOOGLE, "sub", null, false, null, null, "client", nonceClaim,
                NOW.plusSeconds(300));
    }

    private static void assertRefused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.SOCIAL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("claim = SHA-256 hex of the clear nonce (any case) → accepted, consumed until exp + 60 s")
    void matchingNonce() {
        SocialNonceChecker checker = new SocialNonceChecker(nonces, false, clock);
        String hash = SocialNonceChecker.sha256Hex(CLEAR);
        assertThat(hash).hasSize(64).isLowerCase();

        assertThatCode(() -> checker.check(identity(hash.toUpperCase(Locale.ROOT)), CLEAR)).doesNotThrowAnyException();
        assertThat(nonces.used).containsExactly("GOOGLE:" + hash);
        assertThat(nonces.lastExpiry).isEqualTo(NOW.plusSeconds(360));
    }

    @Test
    @DisplayName("same nonce twice → second refused (replay)")
    void replay() {
        SocialNonceChecker checker = new SocialNonceChecker(nonces, false, clock);
        String hash = SocialNonceChecker.sha256Hex(CLEAR);
        checker.check(identity(hash), CLEAR);
        assertRefused(() -> checker.check(identity(hash), CLEAR));
    }

    @Test
    @DisplayName("mismatch, or the clear nonce itself in the claim → refused, nothing consumed")
    void mismatch() {
        SocialNonceChecker checker = new SocialNonceChecker(nonces, false, clock);
        assertRefused(() -> checker.check(identity(SocialNonceChecker.sha256Hex("other-nonce-123456")), CLEAR));
        assertRefused(() -> checker.check(identity(CLEAR), CLEAR));
        assertRefused(() -> checker.check(identity(null), CLEAR));
        assertThat(nonces.used).isEmpty();
    }

    @Test
    @DisplayName("token with a nonce claim but no clear nonce sent → refused (stripping never bypasses)")
    void claimWithoutClearNonce() {
        SocialNonceChecker checker = new SocialNonceChecker(nonces, false, clock);
        assertRefused(() -> checker.check(identity(SocialNonceChecker.sha256Hex(CLEAR)), null));
        assertRefused(() -> checker.check(identity(SocialNonceChecker.sha256Hex(CLEAR)), "  "));
    }

    @Test
    @DisplayName("no nonce at all: accepted when not required (apps in stores), refused when required")
    void noNonce() {
        assertThatCode(() -> new SocialNonceChecker(nonces, false, clock).check(identity(null), null))
                .doesNotThrowAnyException();
        assertRefused(() -> new SocialNonceChecker(nonces, true, clock).check(identity(null), null));
        assertThat(nonces.used).isEmpty();
    }
}
