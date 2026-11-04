package com.keevo.identity.auth.adapter.out.social;

import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.shared.MutableTestClock;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwksSocialTokenVerifier (Story 19.3)")
class JwksSocialTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final String GOOGLE_ISS = "https://accounts.google.com";
    private static final String APPLE_ISS = "https://appleid.apple.com";
    private static final String CLIENT = "client-1.apps.googleusercontent.com";
    private static final String BUNDLE = "com.brefpoint.app";

    private final SocialTestTokens provider = new SocialTestTokens("k1");
    private MutableTestClock clock;
    private StubJwks jwks;

    /** Counts fetches and serves the configured documents in order (the last one repeats). */
    static final class StubJwks implements JwksClient {
        final List<JwksDocument> documents = new ArrayList<>();
        final AtomicInteger fetches = new AtomicInteger();
        RuntimeException failure;

        @Override
        public JwksDocument fetch(String uri) {
            int n = fetches.getAndIncrement();
            if (failure != null) throw failure;
            return documents.get(Math.min(n, documents.size() - 1));
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableTestClock(NOW);
        jwks = new StubJwks();
        jwks.documents.add(new JwksClient.JwksDocument(provider.jwks(), null));
    }

    private JwksSocialTokenVerifier google(List<String> audiences) {
        return new JwksSocialTokenVerifier(SocialProvider.GOOGLE, SocialAuthConfig.GOOGLE_ISSUERS, "https://g/certs",
                audiences, JwksSocialTokenVerifier.EmailRule.REJECT_UNVERIFIED, jwks, clock);
    }

    private JwksSocialTokenVerifier apple() {
        return new JwksSocialTokenVerifier(SocialProvider.APPLE, SocialAuthConfig.APPLE_ISSUERS, "https://a/keys",
                List.of("com.keevo.app", BUNDLE), JwksSocialTokenVerifier.EmailRule.IGNORE_UNVERIFIED, jwks, clock);
    }

    private String googleToken(Map<String, ?> claims) {
        return provider.token(GOOGLE_ISS, CLIENT, "g-sub-1", NOW.plusSeconds(600), claims);
    }

    private static void assertRefused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.SOCIAL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("valid Google token → subject, verified email (lowercased) and names")
    void validGoogle() {
        SocialIdentity id = google(List.of("other", CLIENT)).verify(googleToken(Map.of(
                "email", "Ana@Ex.com", "email_verified", true, "given_name", "Ana", "family_name", "Mballa")));

        assertThat(id.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(id.subject()).isEqualTo("g-sub-1");
        assertThat(id.verifiedEmail()).isEqualTo("ana@ex.com");
        assertThat(id.firstName()).isEqualTo("Ana");
        assertThat(id.lastName()).isEqualTo("Mballa");
    }

    @Test
    @DisplayName("issuer without scheme accounts.google.com is accepted")
    void googleIssuerWithoutScheme() {
        String token = provider.token("accounts.google.com", CLIENT, "s", NOW.plusSeconds(60), Map.of());
        assertThat(google(List.of(CLIENT)).verify(token).subject()).isEqualTo("s");
    }

    @Test
    @DisplayName("wrong audience → SOCIAL_TOKEN_INVALID")
    void wrongAudience() {
        String token = provider.token(GOOGLE_ISS, "someone-else", "s", NOW.plusSeconds(600), Map.of());
        assertRefused(() -> google(List.of(CLIENT)).verify(token));
    }

    @Test
    @DisplayName("wrong issuer → SOCIAL_TOKEN_INVALID")
    void wrongIssuer() {
        String token = provider.token("https://evil.example", CLIENT, "s", NOW.plusSeconds(600), Map.of());
        assertRefused(() -> google(List.of(CLIENT)).verify(token));
    }

    @Test
    @DisplayName("expired beyond the 60 s skew → refused; within the skew → accepted")
    void expiry() {
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        String justExpired = provider.token(GOOGLE_ISS, CLIENT, "s", NOW.minusSeconds(30), Map.of());
        assertThat(verifier.verify(justExpired).subject()).isEqualTo("s");
        String expired = provider.token(GOOGLE_ISS, CLIENT, "s", NOW.minusSeconds(120), Map.of());
        assertRefused(() -> verifier.verify(expired));
    }

    @Test
    @DisplayName("signature by another key with the same kid → refused; garbage token → refused")
    void badSignature() {
        SocialTestTokens forger = new SocialTestTokens("k1");
        String forged = forger.token(GOOGLE_ISS, CLIENT, "s", NOW.plusSeconds(600), Map.of());
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        assertRefused(() -> verifier.verify(forged));
        assertRefused(() -> verifier.verify("not.a.jwt"));
        assertRefused(() -> verifier.verify(""));
    }

    @Test
    @DisplayName("no audience configured → refused without any network call")
    void noAudiences() {
        JwksSocialTokenVerifier verifier = google(List.of(" ", ""));
        assertThat(verifier.isConfigured()).isFalse();
        assertRefused(() -> verifier.verify(googleToken(Map.of())));
        assertThat(jwks.fetches.get()).isZero();
    }

    @Test
    @DisplayName("Google email with email_verified false (or absent) → refused; no email → accepted")
    void googleUnverifiedEmail() {
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        assertRefused(() -> verifier.verify(googleToken(Map.of("email", "a@ex.com", "email_verified", false))));
        assertRefused(() -> verifier.verify(googleToken(Map.of("email", "a@ex.com"))));
        assertThat(verifier.verify(googleToken(Map.of())).email()).isNull();
    }

    @Test
    @DisplayName("Apple email_verified as string \"true\" or boolean → kept; \"false\" → email ignored, token accepted")
    void appleEmailVerified() {
        JwksSocialTokenVerifier verifier = apple();
        String asString = provider.token(APPLE_ISS, BUNDLE, "a-sub", NOW.plusSeconds(600),
                Map.of("email", "x@privaterelay.appleid.com", "email_verified", "true"));
        String asBoolean = provider.token(APPLE_ISS, "com.keevo.app", "a-sub", NOW.plusSeconds(600),
                Map.of("email", "x@privaterelay.appleid.com", "email_verified", true));
        String unverified = provider.token(APPLE_ISS, BUNDLE, "a-sub", NOW.plusSeconds(600),
                Map.of("email", "x@ex.com", "email_verified", "false"));

        assertThat(verifier.verify(asString).verifiedEmail()).isEqualTo("x@privaterelay.appleid.com");
        assertThat(verifier.verify(asBoolean).verifiedEmail()).isEqualTo("x@privaterelay.appleid.com");
        SocialIdentity ignored = verifier.verify(unverified);
        assertThat(ignored.subject()).isEqualTo("a-sub");
        assertThat(ignored.verifiedEmail()).isNull();
    }

    @Test
    @DisplayName("Apple token with the Google issuer → refused")
    void appleWrongIssuer() {
        String token = provider.token(GOOGLE_ISS, BUNDLE, "a-sub", NOW.plusSeconds(600), Map.of());
        assertRefused(() -> apple().verify(token));
    }

    @Test
    @DisplayName("keys cached for Cache-Control max-age, refetched once expired")
    void cacheReuse() {
        jwks.documents.set(0, new JwksClient.JwksDocument(provider.jwks(), Duration.ofMinutes(10)));
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        verifier.verify(googleToken(Map.of()));
        verifier.verify(googleToken(Map.of()));
        clock.advance(Duration.ofMinutes(9));
        verifier.verify(googleToken(Map.of()));
        assertThat(jwks.fetches.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        verifier.verify(provider.token(GOOGLE_ISS, CLIENT, "s", clock.instant().plusSeconds(600), Map.of()));
        assertThat(jwks.fetches.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("unknown kid → one refetch (key rotation picked up); a second unknown kid within 60 s → no refetch")
    void unknownKidRefetch() {
        SocialTestTokens rotated = new SocialTestTokens("k2");
        jwks.documents.add(new JwksClient.JwksDocument(
                "{\"keys\":[" + provider.jwk() + "," + rotated.jwk() + "]}", null));
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        verifier.verify(googleToken(Map.of()));
        assertThat(jwks.fetches.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(61));
        String withNewKey = rotated.token(GOOGLE_ISS, CLIENT, "s", NOW.plusSeconds(600), Map.of());
        assertThat(verifier.verify(withNewKey).subject()).isEqualTo("s");
        assertThat(jwks.fetches.get()).isEqualTo(2);

        String unknown = new SocialTestTokens("k3").token(GOOGLE_ISS, CLIENT, "s", NOW.plusSeconds(600), Map.of());
        assertRefused(() -> verifier.verify(unknown));
        assertRefused(() -> verifier.verify(unknown));
        assertThat(jwks.fetches.get()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(61));
        assertRefused(() -> verifier.verify(unknown));
        assertThat(jwks.fetches.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("JWKS unavailable → refused, and not refetched on every call")
    void jwksDown() {
        jwks.failure = new IllegalStateException("down");
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        assertRefused(() -> verifier.verify(googleToken(Map.of())));
        assertRefused(() -> verifier.verify(googleToken(Map.of())));
        assertThat(jwks.fetches.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Cache-Control max-age parsing")
    void maxAgeParsing() {
        assertThat(RestTemplateJwksClient.maxAge("public, max-age=21600, must-revalidate"))
                .isEqualTo(Duration.ofSeconds(21600));
        assertThat(RestTemplateJwksClient.maxAge("no-cache")).isNull();
        assertThat(RestTemplateJwksClient.maxAge(null)).isNull();
    }

    @Test
    @DisplayName("audience lists: comma separated, blanks dropped")
    void commaList() {
        assertThat(SocialAuthConfig.commaList(" a , ,b ")).containsExactly("a", "b");
        assertThat(SocialAuthConfig.commaList("")).isEmpty();
    }

    @Test
    @DisplayName("keys cached, JWKS down after expiry → still verifies with previous keys, no refetch within 60 s; "
            + "refused once 24 h past their expiry")
    void jwksDownAfterCachedKeys() {
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        verifier.verify(googleToken(Map.of()));                       // cached until NOW + 1 h
        jwks.failure = new IllegalStateException("down");

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(verifier.verify(fresh()).subject()).isEqualTo("g-sub-1");
        assertThat(jwks.fetches.get()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(30));
        assertThat(verifier.verify(fresh()).subject()).isEqualTo("g-sub-1");
        assertThat(jwks.fetches.get()).isEqualTo(2);

        clock.advance(Duration.ofHours(24));                         // past original expiry + 24 h
        assertRefused(() -> verifier.verify(fresh()));
    }

    private String fresh() {
        return provider.token(GOOGLE_ISS, CLIENT, "g-sub-1", clock.instant().plusSeconds(600), Map.of());
    }

    @Test
    @DisplayName("a fetched JWKS without any RSA key keeps the previous keys")
    void emptyJwksKeepsPreviousKeys() {
        jwks.documents.add(new JwksClient.JwksDocument("{\"keys\":[]}", null));
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        verifier.verify(googleToken(Map.of()));
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(verifier.verify(fresh()).subject()).isEqualTo("g-sub-1");
        assertThat(jwks.fetches.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("token without exp → refused")
    void noExpiry() {
        String token = provider.token(GOOGLE_ISS, List.of(CLIENT), "s", null, Map.of(), "RS256");
        assertRefused(() -> google(List.of(CLIENT)).verify(token));
    }

    @Test
    @DisplayName("RS512, HS256 and alg none → refused")
    void otherAlgorithms() {
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        for (String alg : List.of("RS512", "HS256", "none")) {
            String token = provider.token(GOOGLE_ISS, List.of(CLIENT), "s", NOW.plusSeconds(600), Map.of(), alg);
            assertRefused(() -> verifier.verify(token));
        }
    }

    @Test
    @DisplayName("Google with several audiences: azp must be a configured client")
    void googleAzpWithSeveralAudiences() {
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        String otherAzp = provider.token(GOOGLE_ISS, List.of(CLIENT, "other-client"), "s", NOW.plusSeconds(600),
                Map.of("azp", "other-client"), "RS256");
        String noAzp = provider.token(GOOGLE_ISS, List.of(CLIENT, "other-client"), "s", NOW.plusSeconds(600),
                Map.of(), "RS256");
        String ourAzp = provider.token(GOOGLE_ISS, List.of(CLIENT, "other-client"), "s", NOW.plusSeconds(600),
                Map.of("azp", CLIENT), "RS256");
        assertRefused(() -> verifier.verify(otherAzp));
        assertRefused(() -> verifier.verify(noAzp));
        assertThat(verifier.verify(ourAzp).subject()).isEqualTo("s");
    }

    @Test
    @DisplayName("sub longer than 255 chars → refused; 255 accepted")
    void longSubject() {
        JwksSocialTokenVerifier verifier = google(List.of(CLIENT));
        String tooLong = provider.token(GOOGLE_ISS, CLIENT, "x".repeat(256), NOW.plusSeconds(600), Map.of());
        assertRefused(() -> verifier.verify(tooLong));
        String max = provider.token(GOOGLE_ISS, CLIENT, "x".repeat(255), NOW.plusSeconds(600), Map.of());
        assertThat(verifier.verify(max).subject()).hasSize(255);
    }

    @Test
    @DisplayName("Apple config: empty audiences falls back to bundle-id; audiences wins over bundle-id")
    void appleConfigFallback() {
        SocialAuthConfig config = new SocialAuthConfig();
        JwksSocialTokenVerifier fallback = config.appleTokenVerifier("", "com.brefpoint.app", jwks, clock);
        assertThat(fallback.isConfigured()).isTrue();
        assertThat(fallback.verify(provider.token(APPLE_ISS, BUNDLE, "a", NOW.plusSeconds(600), Map.of())).subject())
                .isEqualTo("a");

        JwksSocialTokenVerifier explicit = config.appleTokenVerifier("com.keevo.app", "com.brefpoint.app", jwks, clock);
        assertRefused(() -> explicit.verify(provider.token(APPLE_ISS, BUNDLE, "a", NOW.plusSeconds(600), Map.of())));
        assertThat(explicit.verify(provider.token(APPLE_ISS, "com.keevo.app", "a", NOW.plusSeconds(600), Map.of()))
                .subject()).isEqualTo("a");

        assertThat(config.appleTokenVerifier("", "", jwks, clock).isConfigured()).isFalse();
    }
}
