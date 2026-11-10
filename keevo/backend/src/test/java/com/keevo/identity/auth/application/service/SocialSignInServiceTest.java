package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.model.UserMembershipInfo;
import com.keevo.identity.auth.domain.port.in.LoginSessionResult;
import com.keevo.identity.auth.domain.port.in.SocialSignInCommand;
import com.keevo.identity.auth.domain.port.out.SocialTokenVerifier;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.MutableTestClock;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SocialSignInService (Story 19.3)")
class SocialSignInServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    private static final String EMAIL = "ana@ex.com";

    @Mock UserRepository users;
    @Mock UserAuthenticatorRepository authenticators;
    @Mock com.keevo.identity.auth.domain.port.out.UserMembershipRepository memberships;
    @Mock JwtTokenProvider jwt;
    @Mock PlatformTransactionManager txManager;
    @Mock com.keevo.identity.auth.domain.port.out.SocialNonceRepository nonces;
    @Mock AppleTokenStore appleTokenStore;

    /** Verifier stub per provider: returns the identity set by the test, or throws its failure. */
    static final class StubVerifier implements SocialTokenVerifier {
        final SocialProvider provider;
        SocialIdentity identity;
        RuntimeException failure;

        StubVerifier(SocialProvider provider) { this.provider = provider; }

        @Override public SocialProvider provider() { return provider; }

        @Override public SocialIdentity verify(String token) {
            if (failure != null) throw failure;
            return identity;
        }
    }

    private final StubVerifier google = new StubVerifier(SocialProvider.GOOGLE);
    private final StubVerifier apple = new StubVerifier(SocialProvider.APPLE);
    private SocialSignInService service;

    @BeforeEach
    void setUp() {
        MutableTestClock clock = new MutableTestClock(NOW);
        service = new SocialSignInService(new SocialIdentityVerifier(List.of(google, apple),
                new SocialSignInRateLimiter(clock, 30), new SocialNonceChecker(nonces, false, clock)), users,
                authenticators, memberships, jwt, new TransactionTemplate(txManager), appleTokenStore, clock);
        when(nonces.consume(any(), anyString(), any())).thenReturn(true);
        when(jwt.generateLoginToken(any())).thenReturn("login-tok");
        when(users.findMembershipsWithTenantInfo(any())).thenReturn(List.of());
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(authenticators.findUserId(any(), anyString())).thenReturn(Optional.empty());
        when(authenticators.insertIfAbsent(any(), any(), anyString())).thenReturn(true);
        when(users.insertIfAbsent(any())).thenReturn(true);
    }

    private static SocialIdentity identity(SocialProvider p, String sub, String email, boolean verified) {
        return new SocialIdentity(p, sub, email, verified, null, null);
    }

    private static SocialSignInCommand command(SocialProvider p) {
        return new SocialSignInCommand(p, "token", null, null, "10.0.0.1");
    }

    private static User account(Role role, String email, Instant emailVerifiedAt) {
        return new User(UUID.randomUUID(), "+237699001122", "hash", role, true, NOW, 0, null,
                email, null, null, null, emailVerifiedAt, null);
    }

    private void knownSubject(SocialProvider p, String sub, User user) {
        when(authenticators.findUserId(p, sub)).thenReturn(Optional.of(user.getId()));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("known subject → loginToken + memberships, nothing written")
    void knownSubject() {
        User owner = account(Role.OWNER, EMAIL, NOW);
        knownSubject(SocialProvider.GOOGLE, "g1", owner);
        google.identity = identity(SocialProvider.GOOGLE, "g1", EMAIL, true);
        List<UserMembershipInfo> memberships = List.of(new UserMembershipInfo("KV-1", "B", "OWNER", "kv_1"));
        when(users.findMembershipsWithTenantInfo(owner.getId())).thenReturn(memberships);

        LoginSessionResult result = service.signIn(command(SocialProvider.GOOGLE));

        assertThat(result.loginToken()).isEqualTo("login-tok");
        assertThat(result.memberships()).isEqualTo(memberships);
        verify(jwt).generateLoginToken(owner.getId());
        verify(users, never()).insertIfAbsent(any());
        verify(users, never()).save(any());
        verify(authenticators, never()).insertIfAbsent(any(), any(), anyString());
    }

    @Test
    @DisplayName("new subject, verified email of an OWNER with a verified email → linked, same account")
    void linkOwner() {
        User owner = account(Role.OWNER, EMAIL, NOW.minusSeconds(3600));
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(owner));
        apple.identity = identity(SocialProvider.APPLE, "a1", EMAIL, true);

        service.signIn(command(SocialProvider.APPLE));

        verify(authenticators).insertIfAbsent(owner.getId(), SocialProvider.APPLE, "a1");
        verify(users, never()).insertIfAbsent(any());
        verify(jwt).generateLoginToken(owner.getId());
    }

    @Test
    @DisplayName("19.6 — verified email of an account EMPLOYEE in A and OWNER in B (global role EMPLOYEE) → linked")
    void linkEmployeeHereOwnerElsewhere() {
        User account = account(Role.EMPLOYEE, EMAIL, NOW.minusSeconds(3600));
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(memberships.hasActiveOwnerMembership(account.getId())).thenReturn(true);
        when(memberships.hasAnyMembership(account.getId())).thenReturn(true);
        google.identity = identity(SocialProvider.GOOGLE, "g9", EMAIL, true);

        service.signIn(command(SocialProvider.GOOGLE));

        verify(authenticators).insertIfAbsent(account.getId(), SocialProvider.GOOGLE, "g9");
    }

    @Test
    @DisplayName("verified email of an account member only (no OWNER membership, global role OWNER), or of an "
            + "owner account whose email is unverified → 409 EMAIL_ALREADY_EXISTS")
    void refuseLink() {
        google.identity = identity(SocialProvider.GOOGLE, "g1", EMAIL, true);
        User memberOnly = account(Role.OWNER, EMAIL, NOW); // 19.6 — the global role is not read any more
        when(memberships.hasAnyMembership(memberOnly.getId())).thenReturn(true);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(memberOnly));
        assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.EMAIL_ALREADY_EXISTS);

        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(account(Role.OWNER, EMAIL, null)));
        assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.EMAIL_ALREADY_EXISTS);

        verify(authenticators, never()).insertIfAbsent(any(), any(), anyString());
        verify(users, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("new subject, new email → OWNER account with verified email and names, authenticator, memberships []")
    void createWithEmail() {
        google.identity = new SocialIdentity(SocialProvider.GOOGLE, "g1", EMAIL, true, "Ana", "Mballa");

        LoginSessionResult result = service.signIn(new SocialSignInCommand(SocialProvider.GOOGLE, "t", "Anna", null,
                null));

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).insertIfAbsent(created.capture());
        User u = created.getValue();
        assertThat(u.getRole()).isEqualTo(Role.OWNER);
        assertThat(u.getEmail()).isEqualTo(EMAIL);
        assertThat(u.getEmailVerifiedAt()).isEqualTo(NOW);
        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.hasPassword()).isFalse();
        assertThat(u.getUsername()).isNull();
        assertThat(u.getFirstName()).isEqualTo("Anna");   // body wins
        assertThat(u.getLastName()).isEqualTo("Mballa");  // token fallback
        verify(authenticators).insertIfAbsent(u.getId(), SocialProvider.GOOGLE, "g1");
        assertThat(result.memberships()).isEmpty();
    }

    @Test
    @DisplayName("Apple without email, known subject → signed in")
    void appleNoEmailKnown() {
        User owner = account(Role.OWNER, null, null);
        knownSubject(SocialProvider.APPLE, "a1", owner);
        apple.identity = identity(SocialProvider.APPLE, "a1", null, false);

        service.signIn(command(SocialProvider.APPLE));

        verify(jwt).generateLoginToken(owner.getId());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("Apple without email, unknown subject → account with technical username apple-<12 hex> only")
    void appleNoEmailUnknown() {
        apple.identity = identity(SocialProvider.APPLE, "001234.abcdef.1234", "hidden@ex.com", false);

        service.signIn(command(SocialProvider.APPLE));

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).insertIfAbsent(created.capture());
        User u = created.getValue();
        assertThat(u.getEmail()).isNull();                // unverified email ignored
        assertThat(u.getEmailVerifiedAt()).isNull();
        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.getUsername()).matches("^apple-[0-9a-f]{12}$")
                .isEqualTo(SocialSignInService.technicalUsername(apple.identity, 12));
        verify(authenticators).insertIfAbsent(u.getId(), SocialProvider.APPLE, "001234.abcdef.1234");
    }

    @Test
    @DisplayName("technical username held by an unrelated account → longer suffix")
    void usernameCollision() {
        apple.identity = identity(SocialProvider.APPLE, "a-sub", null, false);
        when(users.insertIfAbsent(any())).thenReturn(false, true);

        service.signIn(command(SocialProvider.APPLE));

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users, times(2)).insertIfAbsent(created.capture());
        assertThat(created.getAllValues().get(0).getUsername()).hasSize("apple-".length() + 12);
        assertThat(created.getAllValues().get(1).getUsername()).hasSize("apple-".length() + 20);
    }

    @Test
    @DisplayName("concurrent first login: user insert conflicts, subject now linked → that account, no second insert")
    void concurrentSameSubject() {
        apple.identity = identity(SocialProvider.APPLE, "a1", null, false);
        User winner = account(Role.OWNER, null, null);
        when(users.insertIfAbsent(any())).thenReturn(false);
        when(authenticators.findUserId(SocialProvider.APPLE, "a1"))
                .thenReturn(Optional.empty(), Optional.of(winner.getId()));
        when(users.findById(winner.getId())).thenReturn(Optional.of(winner));

        service.signIn(command(SocialProvider.APPLE));

        verify(users, times(1)).insertIfAbsent(any());
        verify(jwt).generateLoginToken(winner.getId());
    }

    @Test
    @DisplayName("concurrent first login with the same email from another provider → linked to the committed account")
    void concurrentSameEmail() {
        google.identity = identity(SocialProvider.GOOGLE, "g1", EMAIL, true);
        User committed = account(Role.OWNER, EMAIL, NOW);
        when(users.insertIfAbsent(any())).thenReturn(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty(), Optional.of(committed));

        service.signIn(command(SocialProvider.GOOGLE));

        verify(authenticators).insertIfAbsent(committed.getId(), SocialProvider.GOOGLE, "g1");
        verify(jwt).generateLoginToken(committed.getId());
    }

    @Test
    @DisplayName("subject linked to another account between our user insert and authenticator insert → rolled back (409)")
    void authenticatorConflictAfterCreate() {
        google.identity = identity(SocialProvider.GOOGLE, "g1", EMAIL, true);
        when(authenticators.insertIfAbsent(any(), any(), anyString())).thenReturn(false);

        assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.USER_ALREADY_EXISTS);
        verify(txManager).rollback(any());
    }

    @Test
    @DisplayName("known subject, account without email, verified email unused elsewhere → email set and verified")
    void addEmailToKnownAccount() {
        User owner = account(Role.OWNER, null, null);
        knownSubject(SocialProvider.APPLE, "a1", owner);
        apple.identity = identity(SocialProvider.APPLE, "a1", EMAIL, true);

        when(users.setEmailIfAbsent(owner.getId(), EMAIL, NOW)).thenReturn(1);

        service.signIn(command(SocialProvider.APPLE));

        verify(users).setEmailIfAbsent(owner.getId(), EMAIL, NOW);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("known subject, verified email already held by another account → account unchanged")
    void emailUsedElsewhere() {
        User owner = account(Role.OWNER, null, null);
        knownSubject(SocialProvider.APPLE, "a1", owner);
        when(users.setEmailIfAbsent(any(), anyString(), any())).thenReturn(0);
        apple.identity = identity(SocialProvider.APPLE, "a1", EMAIL, true);

        service.signIn(command(SocialProvider.APPLE));

        verify(users).setEmailIfAbsent(owner.getId(), EMAIL, NOW);
        verify(users, never()).save(any());
        verify(jwt).generateLoginToken(owner.getId());
    }

    @Test
    @DisplayName("SUPER_ADMIN by subject or by email → 401 SOCIAL_TOKEN_INVALID, nothing linked")
    void superAdminRefused() {
        User admin = account(Role.SUPER_ADMIN, EMAIL, NOW);
        knownSubject(SocialProvider.GOOGLE, "g-admin", admin);
        google.identity = identity(SocialProvider.GOOGLE, "g-admin", null, false);
        assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.SOCIAL_TOKEN_INVALID);

        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(admin));
        google.identity = identity(SocialProvider.GOOGLE, "g-other", EMAIL, true);
        assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.SOCIAL_TOKEN_INVALID);

        verify(authenticators, never()).insertIfAbsent(any(), any(), anyString());
        verify(jwt, never()).generateLoginToken(any());
    }

    @Test
    @DisplayName("token refused by the verifier → SOCIAL_TOKEN_INVALID, no database access")
    void tokenRefused() {
        google.failure = new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
        assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.SOCIAL_TOKEN_INVALID);
        verifyNoInteractions(users, authenticators, txManager);
    }

    @Test
    @DisplayName("valid sign-ins are never limited; after 30 refused tokens from one IP → 429 before verifying")
    void rateLimitCountsOnlyFailures() {
        User owner = account(Role.OWNER, EMAIL, NOW);
        knownSubject(SocialProvider.GOOGLE, "g1", owner);
        knownSubject(SocialProvider.APPLE, "g1", owner);
        google.identity = identity(SocialProvider.GOOGLE, "g1", null, false);
        apple.identity = identity(SocialProvider.APPLE, "g1", null, false);
        for (int i = 0; i < 50; i++) {
            service.signIn(command(SocialProvider.GOOGLE));                // shared NAT IP, valid tokens
        }

        google.failure = new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
        for (int i = 0; i < 30; i++) {
            assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.SOCIAL_TOKEN_INVALID);
        }
        google.failure = null;
        assertCode(() -> service.signIn(command(SocialProvider.GOOGLE)), ErrorCode.RATE_LIMIT_EXCEEDED);
        // Other IP and other provider have their own count.
        assertThat(service.signIn(new SocialSignInCommand(SocialProvider.GOOGLE, "t", null, null, "10.0.0.2"))
                .loginToken()).isEqualTo("login-tok");
        assertThat(service.signIn(command(SocialProvider.APPLE)).loginToken()).isEqualTo("login-tok");
    }

    @Test
    @DisplayName("two verifiers for one provider → IllegalStateException at construction")
    void duplicateVerifier() {
        MutableTestClock clock = new MutableTestClock(NOW);
        assertThatThrownBy(() -> new SocialIdentityVerifier(List.of(google, new StubVerifier(SocialProvider.GOOGLE)),
                new SocialSignInRateLimiter(clock, 30), new SocialNonceChecker(nonces, false, clock)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("names cut at 100 chars never split a surrogate pair")
    void nameTruncation() {
        google.identity = identity(SocialProvider.GOOGLE, "g1", EMAIL, true);
        String emojiAtCut = "a".repeat(99) + "\uD83D\uDE00" + "b";            // high surrogate at index 99
        service.signIn(new SocialSignInCommand(SocialProvider.GOOGLE, "t", emojiAtCut, "c".repeat(150), null));

        ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
        verify(users).insertIfAbsent(created.capture());
        assertThat(created.getValue().getFirstName()).isEqualTo("a".repeat(99));
        assertThat(created.getValue().getLastName()).hasSize(100);
    }

    @Test
    @DisplayName("AC — account created by Google, then Apple with the same verified email → one user, two authenticators")
    void googleThenApple() {
        Map<UUID, User> stored = new HashMap<>();
        when(users.insertIfAbsent(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            stored.put(u.getId(), u);
            return true;
        });
        when(users.findByEmail(EMAIL)).thenAnswer(inv -> stored.values().stream().findFirst());

        google.identity = identity(SocialProvider.GOOGLE, "g1", EMAIL, true);
        service.signIn(command(SocialProvider.GOOGLE));
        apple.identity = identity(SocialProvider.APPLE, "a1", EMAIL, true);
        service.signIn(command(SocialProvider.APPLE));

        assertThat(stored).hasSize(1);
        UUID id = stored.keySet().iterator().next();
        verify(authenticators).insertIfAbsent(id, SocialProvider.GOOGLE, "g1");
        verify(authenticators).insertIfAbsent(id, SocialProvider.APPLE, "a1");
        verify(jwt, times(2)).generateLoginToken(eq(id));
    }

    // ── Story 19.3b ──────────────────────────────────────────────────────────

    private static SocialIdentity withNonce(SocialProvider p, String sub, String nonceClaim) {
        return new SocialIdentity(p, sub, null, false, null, null, "com.keevo.app", nonceClaim,
                NOW.plusSeconds(600));
    }

    @Test
    @DisplayName("19.3b — nonce matching the claim is consumed once, then the replay is refused and counted")
    void nonceConsumedOnceThenReplayRefused() {
        String clear = "0123456789abcdef-nonce";
        apple.identity = withNonce(SocialProvider.APPLE, "a1", SocialNonceChecker.sha256Hex(clear));
        when(nonces.consume(eq(SocialProvider.APPLE), anyString(), any())).thenReturn(true, false);
        SocialSignInCommand cmd = new SocialSignInCommand(SocialProvider.APPLE, "t", null, null, "10.0.0.9", clear,
                null);

        assertThat(service.signIn(cmd).loginToken()).isEqualTo("login-tok");
        verify(nonces).consume(SocialProvider.APPLE, SocialNonceChecker.sha256Hex(clear), NOW.plusSeconds(660));
        assertCode(() -> service.signIn(cmd), ErrorCode.SOCIAL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("19.3b — Apple code handed to the token store after the committed sign-in; not on a refused one")
    void appleCodeStoredAfterCommit() {
        apple.identity = identity(SocialProvider.APPLE, "a1", null, false);
        service.signIn(new SocialSignInCommand(SocialProvider.APPLE, "t", null, null, null, null, "code-1"));
        verify(appleTokenStore).storeIfPossible(apple.identity, "code-1");

        apple.failure = new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID);
        assertCode(() -> service.signIn(new SocialSignInCommand(SocialProvider.APPLE, "t", null, null, null, null,
                "code-2")), ErrorCode.SOCIAL_TOKEN_INVALID);
        verify(appleTokenStore, never()).storeIfPossible(any(), eq("code-2"));
    }
}
