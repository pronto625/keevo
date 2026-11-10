package com.keevo.identity.auth.application.service;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.SocialIdentity;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.identity.auth.domain.model.User;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase.LinkCommand;
import com.keevo.identity.auth.domain.port.in.SocialAccountUseCase.LinkedSocialAccount;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository;
import com.keevo.identity.auth.domain.port.out.UserAuthenticatorRepository.LinkedIdentity;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SocialAccountService (Story 19.3b)")
class SocialAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    @Mock SocialIdentityVerifier verifier;
    @Mock UserRepository users;
    @Mock UserAuthenticatorRepository authenticators;
    @Mock AppleTokenStore appleTokenStore;
    @Mock PlatformTransactionManager txManager;

    private SocialAccountService service;
    private User member;

    @BeforeEach
    void setUp() {
        service = new SocialAccountService(verifier, users, authenticators, appleTokenStore,
                new TransactionTemplate(txManager));
        member = user(Role.EMPLOYEE, "+237699001122", "hash", "ana@ex.com");
        when(users.lockById(member.getId())).thenReturn(true);
        when(users.findById(member.getId())).thenReturn(Optional.of(member));
        when(authenticators.findUserId(any(), anyString())).thenReturn(Optional.empty());
        when(authenticators.findByUser(any())).thenReturn(List.of());
        when(authenticators.insertIfAbsent(any(), any(), anyString())).thenReturn(true);
    }

    private static User user(Role role, String phone, String passwordHash, String email) {
        return new User(UUID.randomUUID(), phone, passwordHash, role, true, NOW, 0, null,
                email, null, null, null, null, null);
    }

    private SocialIdentity proven(SocialProvider p, String sub) {
        SocialIdentity id = new SocialIdentity(p, sub, null, false, null, null);
        when(verifier.verify(any(), anyString(), any(), any())).thenReturn(id);
        return id;
    }

    private LinkCommand link(SocialProvider p, String code) {
        return new LinkCommand(member.getId(), p, "token", null, code, "10.0.0.1");
    }

    private static LinkedIdentity row(SocialProvider p, String sub) {
        return new LinkedIdentity(UUID.randomUUID(), p, sub, NOW);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("list → provider and date only, in repository order")
    void list() {
        when(authenticators.findByUser(member.getId()))
                .thenReturn(List.of(row(SocialProvider.APPLE, "a"), row(SocialProvider.GOOGLE, "g")));
        assertThat(service.list(member.getId())).containsExactly(
                new LinkedSocialAccount(SocialProvider.APPLE, NOW), new LinkedSocialAccount(SocialProvider.GOOGLE, NOW));
    }

    @Test
    @DisplayName("link new Google (member whose sign-in got 409) → inserted under the user lock, no email change")
    void linkNew() {
        proven(SocialProvider.GOOGLE, "g1");
        when(authenticators.findByUser(member.getId()))
                .thenReturn(List.of(), List.of(row(SocialProvider.GOOGLE, "g1")));

        assertThat(service.link(link(SocialProvider.GOOGLE, null)))
                .containsExactly(new LinkedSocialAccount(SocialProvider.GOOGLE, NOW));
        verify(users).lockById(member.getId());
        verify(authenticators).insertIfAbsent(member.getId(), SocialProvider.GOOGLE, "g1");
        verify(users, never()).setEmailIfAbsent(any(), any(), any());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("already linked to this user → no insert, links returned; Apple code still handed to the store")
    void linkIdempotent() {
        SocialIdentity id = proven(SocialProvider.APPLE, "a1");
        when(authenticators.findUserId(SocialProvider.APPLE, "a1")).thenReturn(Optional.of(member.getId()));

        service.link(link(SocialProvider.APPLE, "code"));
        verify(authenticators, never()).insertIfAbsent(any(), any(), anyString());
        verify(appleTokenStore).storeIfPossible(id, "code");
    }

    @Test
    @DisplayName("identity linked to another account → 409 SOCIAL_ACCOUNT_LINKED_ELSEWHERE, nothing stored")
    void linkedElsewhere() {
        proven(SocialProvider.APPLE, "a1");
        when(authenticators.findUserId(SocialProvider.APPLE, "a1")).thenReturn(Optional.of(UUID.randomUUID()));
        assertCode(() -> service.link(link(SocialProvider.APPLE, "code")), ErrorCode.SOCIAL_ACCOUNT_LINKED_ELSEWHERE);
        verify(appleTokenStore, never()).storeIfPossible(any(), any());
    }

    @Test
    @DisplayName("concurrent link of the same identity by another account → 409 SOCIAL_ACCOUNT_LINKED_ELSEWHERE")
    void concurrentLinkElsewhere() {
        proven(SocialProvider.GOOGLE, "g1");
        when(authenticators.insertIfAbsent(any(), any(), anyString())).thenReturn(false);
        when(authenticators.findUserId(SocialProvider.GOOGLE, "g1"))
                .thenReturn(Optional.empty(), Optional.of(UUID.randomUUID()));
        assertCode(() -> service.link(link(SocialProvider.GOOGLE, null)), ErrorCode.SOCIAL_ACCOUNT_LINKED_ELSEWHERE);
    }

    @Test
    @DisplayName("second Google identity on the account → 409 SOCIAL_PROVIDER_ALREADY_LINKED")
    void providerAlreadyLinked() {
        proven(SocialProvider.GOOGLE, "g2");
        when(authenticators.findByUser(member.getId())).thenReturn(List.of(row(SocialProvider.GOOGLE, "g1")));
        assertCode(() -> service.link(link(SocialProvider.GOOGLE, null)), ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
        verify(authenticators, never()).insertIfAbsent(any(), any(), anyString());
    }

    @Test
    @DisplayName("super-admin never links → SOCIAL_TOKEN_INVALID")
    void superAdmin() {
        User admin = user(Role.SUPER_ADMIN, "+237699000000", "hash", null);
        when(users.lockById(admin.getId())).thenReturn(true);
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        proven(SocialProvider.GOOGLE, "g1");
        assertCode(() -> service.link(new LinkCommand(admin.getId(), SocialProvider.GOOGLE, "t", null, null, null)),
                ErrorCode.SOCIAL_TOKEN_INVALID);
    }

    @Test
    @DisplayName("refused token → verifier's error, no transaction work")
    void refusedToken() {
        when(verifier.verify(any(), anyString(), any(), any()))
                .thenThrow(new DomainException(ErrorCode.SOCIAL_TOKEN_INVALID));
        assertCode(() -> service.link(link(SocialProvider.GOOGLE, null)), ErrorCode.SOCIAL_TOKEN_INVALID);
        verify(users, never()).lockById(any());
    }

    @Test
    @DisplayName("unlink Apple of a phone account → rows unlinked (revocation queued by the repository)")
    void unlinkWithOtherWayIn() {
        when(authenticators.findByUser(member.getId())).thenReturn(List.of(row(SocialProvider.APPLE, "a1")));
        service.unlink(member.getId(), SocialProvider.APPLE);
        verify(users).lockById(member.getId());
        verify(authenticators).unlink(member.getId(), SocialProvider.APPLE);
    }

    @Test
    @DisplayName("unlink not linked → no-op")
    void unlinkNotLinked() {
        service.unlink(member.getId(), SocialProvider.GOOGLE);
        verify(authenticators, never()).unlink(any(), any());
    }

    @Test
    @DisplayName("Apple-only account (technical username, no password/phone/email) → 409 LAST_SIGN_IN_METHOD")
    void unlinkLastMethod() {
        User appleOnly = new User(UUID.randomUUID(), null, null, Role.OWNER, true, NOW, 0, null,
                null, "apple-0123456789ab", null, null, null, null);
        when(users.lockById(appleOnly.getId())).thenReturn(true);
        when(users.findById(appleOnly.getId())).thenReturn(Optional.of(appleOnly));
        when(authenticators.findByUser(appleOnly.getId())).thenReturn(List.of(row(SocialProvider.APPLE, "a1")));

        assertCode(() -> service.unlink(appleOnly.getId(), SocialProvider.APPLE), ErrorCode.LAST_SIGN_IN_METHOD);
        verify(authenticators, never()).unlink(any(), any());
    }

    @Test
    @DisplayName("Apple-only account that also has Google → Apple can be unlinked")
    void unlinkWithOtherProvider() {
        User social = new User(UUID.randomUUID(), null, null, Role.OWNER, true, NOW, 0, null,
                null, "apple-0123456789ab", null, null, null, null);
        when(users.lockById(social.getId())).thenReturn(true);
        when(users.findById(social.getId())).thenReturn(Optional.of(social));
        when(authenticators.findByUser(social.getId()))
                .thenReturn(List.of(row(SocialProvider.APPLE, "a1"), row(SocialProvider.GOOGLE, "g1")));

        service.unlink(social.getId(), SocialProvider.APPLE);
        verify(authenticators).unlink(social.getId(), SocialProvider.APPLE);
    }

    @Test
    @DisplayName("unknown user (lock fails) → USER_NOT_FOUND")
    void unknownUser() {
        assertCode(() -> service.unlink(UUID.randomUUID(), SocialProvider.APPLE), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("link hands provider, token, nonce and client IP to the identity verifier")
    void linkPassesVerificationInputs() {
        proven(SocialProvider.GOOGLE, "g1");
        service.link(new LinkCommand(member.getId(), SocialProvider.GOOGLE, "tok", "nonce-0123456789", null,
                "10.0.0.7"));
        verify(verifier).verify(SocialProvider.GOOGLE, "tok", "nonce-0123456789", "10.0.0.7");
        verify(appleTokenStore, never()).storeIfPossible(any(), any());
    }

    @Test
    @DisplayName("Apple relay email is not a way in once Apple is unlinked → LAST_SIGN_IN_METHOD; still one for Google")
    void appleRelayEmail() {
        User relay = new User(UUID.randomUUID(), null, null, Role.OWNER, true, NOW, 0, null,
                "abc123@privaterelay.appleid.com", null, null, null, NOW, null);
        when(users.lockById(relay.getId())).thenReturn(true);
        when(users.findById(relay.getId())).thenReturn(Optional.of(relay));
        when(authenticators.findByUser(relay.getId())).thenReturn(List.of(row(SocialProvider.APPLE, "a1")));
        assertCode(() -> service.unlink(relay.getId(), SocialProvider.APPLE), ErrorCode.LAST_SIGN_IN_METHOD);

        when(authenticators.findByUser(relay.getId()))
                .thenReturn(List.of(row(SocialProvider.APPLE, "a1"), row(SocialProvider.GOOGLE, "g1")));
        service.unlink(relay.getId(), SocialProvider.GOOGLE);
        verify(authenticators).unlink(relay.getId(), SocialProvider.GOOGLE);
    }
}
