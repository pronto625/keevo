package com.keevo.migration.domain.service;

import com.keevo.identity.api.LegacyIdentityImportPort.AccountMatch;
import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.IdentityResolution;
import com.keevo.migration.domain.model.IdentityResolution.Action;
import com.keevo.migration.domain.model.SourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 24.7 — the identity bridge's decisions (pure) and their effect on the validation. */
@DisplayName("24.7 — identity bridge (link, invite, block)")
class IdentityBridgeTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private static final String HASH = "$2b$10$abcdefghijklmnopqrstuuAbCdEfGhIjKlMnOpQrStUvWxYz01234";
    private final UUID keevoA = UUID.randomUUID();
    private final UUID keevoB = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();

    private BrefAccount owner(boolean phoneVerified, boolean emailVerified) {
        return new BrefAccount(ownerId, "owner", "awa@example.test", emailVerified, "+237600000001", phoneVerified,
                null, null, "Awa", "Test", null, true, false, "email", null, T, T, null);
    }

    private BrefAccount member(String phone, String email, boolean emailVerified, String username, boolean active) {
        return new BrefAccount(UUID.randomUUID(), "seller", email, emailVerified, phone, false, username, HASH, "Jean",
                "Test", shopId.toString(), active, false, "email", null, T, T, null);
    }

    private AccountMatch match(ConflictKind kind, UUID user) {
        return new AccountMatch(kind, user, true, true, false);
    }

    private static List<String> codes(IdentityResolution r) {
        return r.cases().stream().map(BlockedCase::code).toList();
    }

    @Test
    @DisplayName("no match: a new account; owner phone confirmed in Bref: linked; phone and verified email of the same "
            + "account: linked")
    void createAndLink() {
        assertThat(IdentityBridge.resolve(owner(true, true), true, List.of()).action()).isEqualTo(Action.CREATE);
        IdentityResolution byPhone = IdentityBridge.resolve(owner(true, false), true,
                List.of(match(ConflictKind.PHONE, keevoA)));
        assertThat(byPhone.action()).isEqualTo(Action.LINK);
        assertThat(byPhone.keevoAccountId()).isEqualTo(keevoA);
        IdentityResolution both = IdentityBridge.resolve(owner(false, true), true,
                List.of(match(ConflictKind.PHONE, keevoA), match(ConflictKind.EMAIL, keevoA)));
        assertThat(both.action()).isEqualTo(Action.LINK);
        IdentityResolution byEmail = IdentityBridge.resolve(owner(false, true), true,
                List.of(match(ConflictKind.EMAIL, keevoB)));
        assertThat(byEmail.keevoAccountId()).isEqualTo(keevoB);
    }

    @Test
    @DisplayName("owner: phone not confirmed in Bref, or email not verified on either side → IDENTITY_UNPROVEN")
    void ownerUnproven() {
        assertThat(codes(IdentityBridge.resolve(owner(false, false), true,
                List.of(match(ConflictKind.PHONE, keevoA))))).containsExactly("IDENTITY_UNPROVEN");
        assertThat(codes(IdentityBridge.resolve(owner(true, false), true,
                List.of(match(ConflictKind.EMAIL, keevoA))))).containsExactly("IDENTITY_UNPROVEN");
        AccountMatch unverifiedKeevo = new AccountMatch(ConflictKind.EMAIL, keevoA, true, false, false);
        assertThat(codes(IdentityBridge.resolve(owner(true, true), true, List.of(unverifiedKeevo))))
                .containsExactly("IDENTITY_UNPROVEN");
    }

    @Test
    @DisplayName("phone and email of two accounts → IDENTITY_AMBIGUOUS; username taken, username = a phone, phone = "
            + "a username, social identity of another account → listed cases; same id → ACCOUNT_ID_TAKEN")
    void blockingCases() {
        assertThat(codes(IdentityBridge.resolve(owner(true, true), true,
                List.of(match(ConflictKind.PHONE, keevoA), match(ConflictKind.EMAIL, keevoB)))))
                .containsExactly("IDENTITY_AMBIGUOUS");
        BrefAccount seller = member(null, null, false, "vendeur", true);
        assertThat(codes(IdentityBridge.resolve(seller, false, List.of(match(ConflictKind.USERNAME, keevoA)))))
                .containsExactly("USERNAME_TAKEN");
        assertThat(codes(IdentityBridge.resolve(seller, false,
                List.of(match(ConflictKind.USERNAME_IS_PHONE, keevoA))))).containsExactly("USERNAME_IS_PHONE");
        assertThat(codes(IdentityBridge.resolve(owner(true, true), true,
                List.of(match(ConflictKind.PHONE_IS_USERNAME, keevoA))))).containsExactly("ACCOUNT_EXISTS");
        assertThat(codes(IdentityBridge.resolve(owner(true, true), true,
                List.of(match(ConflictKind.PHONE, keevoA), match(ConflictKind.SOCIAL, keevoB)))))
                .containsExactly("SOCIAL_TAKEN");
        assertThat(codes(IdentityBridge.resolve(owner(true, true), true,
                List.of(match(ConflictKind.SAME_ID, ownerId))))).containsExactly("ACCOUNT_ID_TAKEN");
        // the linked account's own username / identity is no conflict
        assertThat(IdentityBridge.resolve(owner(true, true), true, List.of(match(ConflictKind.PHONE, keevoA),
                match(ConflictKind.USERNAME, keevoA), match(ConflictKind.SOCIAL, keevoA))).action())
                .isEqualTo(Action.LINK);
    }

    @Test
    @DisplayName("an inactive or platform-admin Keevo account is never linked")
    void unavailableTarget() {
        assertThat(codes(IdentityBridge.resolve(owner(true, true), true,
                List.of(new AccountMatch(ConflictKind.PHONE, keevoA, false, true, false)))))
                .containsExactly("LINK_TARGET_UNAVAILABLE");
        assertThat(codes(IdentityBridge.resolve(owner(true, true), true,
                List.of(new AccountMatch(ConflictKind.PHONE, keevoA, true, true, true)))))
                .containsExactly("LINK_TARGET_UNAVAILABLE");
    }

    @Test
    @DisplayName("member: phone match not confirmed → invitation (skipped when deactivated); email typed by the "
            + "owner → created without it, blocked when it was the only identifier; proven email → linked")
    void members() {
        IdentityResolution invite = IdentityBridge.resolve(member("+237600000002", null, false, "jean", true), false,
                List.of(match(ConflictKind.PHONE, keevoA)));
        assertThat(invite.action()).isEqualTo(Action.INVITE);
        assertThat(invite.keevoAccountId()).isEqualTo(keevoA);
        assertThat(IdentityBridge.resolve(member("+237600000002", null, false, "jean", false), false,
                List.of(match(ConflictKind.PHONE, keevoA))).action()).isEqualTo(Action.SKIP);
        IdentityResolution dropped = IdentityBridge.resolve(member(null, "j@example.test", false, "jean", true),
                false, List.of(match(ConflictKind.EMAIL, keevoA)));
        assertThat(dropped.action()).isEqualTo(Action.CREATE);
        assertThat(dropped.dropEmail()).isTrue();
        assertThat(codes(IdentityBridge.resolve(member(null, "j@example.test", false, null, true), false,
                List.of(match(ConflictKind.EMAIL, keevoA))))).containsExactly("IDENTITY_UNPROVEN");
        assertThat(IdentityBridge.resolve(member(null, "j@example.test", true, "jean", true), false,
                List.of(match(ConflictKind.EMAIL, keevoA))).action()).isEqualTo(Action.LINK);
    }

    @Test
    @DisplayName("validation: a linked account skips the Bref field rules; two Bref accounts on one Keevo account "
            + "block; the bridge's cases are listed")
    void validation() {
        BrefAccount o = owner(true, true);
        BrefAccount badName = new BrefAccount(UUID.randomUUID(), "seller", null, false, "+237600000003", true,
                "x".repeat(60), HASH, "n".repeat(150), null, shopId.toString(), true, false, "email", null, T, T,
                null);
        BrefShop shop = new BrefShop(shopId, ownerId, "Boutique", "Adresse", "electronics", null, null, null, null,
                null, "CM", true, T, T, null, null);
        BrefSnapshot s = new BrefSnapshot(o, List.of(shop), List.of(badName), SourceKind.DATABASE);
        BrefSnapshotValidator.ShopRules valid = x -> Optional.empty();
        assertThat(BrefSnapshotValidator.validate(s, Map.of(o.id(), IdentityResolution.link(keevoA),
                badName.id(), IdentityResolution.link(keevoB)), valid)).isEmpty();
        assertThat(BrefSnapshotValidator.validate(s, Map.of(o.id(), IdentityResolution.link(keevoA)), valid))
                .extracting(BlockedCase::code).containsExactly("USERNAME_INVALID", "NAME_TOO_LONG");
        assertThat(BrefSnapshotValidator.validate(s, Map.of(o.id(), IdentityResolution.link(keevoA),
                badName.id(), IdentityResolution.invite(keevoA)), valid))
                .extracting(BlockedCase::code, BlockedCase::sourceId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("IDENTITY_AMBIGUOUS", badName.id()));
    }

    @Test
    @DisplayName("social identity: Google / Apple with a subject only; unknown providers and blanks ignored")
    void socialProvider() {
        BrefAccount google = new BrefAccount(ownerId, "owner", null, false, null, true, null, null, null, null, null,
                true, false, "google", " 1234567890 ", T, T, null);
        assertThat(BrefMappings.socialProvider(google)).isEqualTo("GOOGLE");
        assertThat(BrefMappings.toLegacy(google).socialSubject()).isEqualTo("1234567890");
        BrefAccount apple = new BrefAccount(ownerId, "owner", null, false, null, true, null, null, null, null, null,
                true, false, "Apple", "000123.abc", T, T, null);
        assertThat(BrefMappings.socialProvider(apple)).isEqualTo("APPLE");
        BrefAccount noSubject = new BrefAccount(ownerId, "owner", null, false, null, true, null, null, null, null,
                null, true, false, "google", " ", T, T, null);
        assertThat(BrefMappings.socialProvider(noSubject)).isNull();
        assertThat(BrefMappings.toLegacy(noSubject).socialProvider()).isNull();
        assertThat(BrefMappings.socialProvider(owner(true, true))).isNull();
    }
}
