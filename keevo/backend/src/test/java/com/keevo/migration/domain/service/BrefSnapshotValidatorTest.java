package com.keevo.migration.domain.service;

import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.identity.api.Role;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.Canonical;
import com.keevo.migration.domain.model.SourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("24.2 — Bref snapshot validation and mappings (pure)")
class BrefSnapshotValidatorTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private static final String HASH = "$2b$10$abcdefghijklmnopqrstuuAbCdEfGhIjKlMnOpQrStUvWxYz01234";
    private static final BrefSnapshotValidator.ShopRules VALID = s -> Optional.empty();

    private final UUID shopId = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    private static BrefAccount account(String role, String phone, String email, String username, String hash,
                                       String shopId, boolean active, Instant deleted) {
        return new BrefAccount("owner".equals(role) ? OWNER_ID : UUID.randomUUID(), role, email, email != null, phone,
                phone != null, username, hash,
                "Awa", "Test", shopId, active, false, "email", null, T, T, deleted);
    }

    private BrefShop shop() {
        return new BrefShop(shopId, OWNER_ID, "Boutique", "Adresse", "electronics", null, null, null, null,
                null, "CM", true, T, T, null, null);
    }

    private BrefAccount owner(String phone) {
        return account("owner", phone, "owner@example.test", null, null, null, true, null);
    }

    private BrefAccount member(String role, String username) {
        return account(role, null, null, username, HASH, shopId.toString(), true, null);
    }

    private List<String> codes(BrefSnapshot s, Map<UUID, Set<ConflictKind>> conflicts, Set<UUID> imported) {
        return BrefSnapshotValidator.validate(s, conflicts, imported, VALID).stream().map(BlockedCase::code).toList();
    }

    @Test
    @DisplayName("a clean owner, shop and team: no case")
    void clean() {
        BrefSnapshot s = new BrefSnapshot(owner("+237600000001"), List.of(shop()),
                List.of(member("admin", "gerant.1"), member("seller", "vendeur_2"), member("viewer", "obs-3")),
                SourceKind.DATABASE);
        assertThat(codes(s, Map.of(), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("every blocking rule: owner state, identifiers, role, shop, duplicates, conflicts")
    void cases() {
        BrefAccount deletedOwner = account("owner", "+237600000001", null, null, null, null, true, T);
        assertThat(codes(new BrefSnapshot(deletedOwner, List.of(), List.of(), SourceKind.EXPORT), Map.of(), Set.of()))
                .containsExactly("OWNER_DELETED", "OWNER_NO_SHOP");
        BrefAccount inactive = account("owner", "+237600000001", null, null, null, null, false, null);
        assertThat(codes(new BrefSnapshot(inactive, List.of(shop()), List.of(), SourceKind.EXPORT), Map.of(),
                Set.of())).containsExactly("OWNER_INACTIVE");

        BrefAccount noId = account("seller", null, null, null, HASH, shopId.toString(), true, null);
        BrefAccount badUser = member("seller", "jean dupont");
        BrefAccount badRole = member("superadmin", "root.1");
        BrefAccount badShop = account("seller", null, null, "ailleurs", HASH, UUID.randomUUID().toString(), true,
                null);
        BrefAccount badHash = account("admin", null, null, "md5user", "5f4dcc3b5aa765d61d8327deb882cf99",
                shopId.toString(), true, null);
        BrefAccount dup = member("seller", "GERANT.1");
        BrefAccount first = member("admin", "gerant.1");
        BrefAccount deleted = account("viewer", "abc", null, null, null, shopId.toString(), true, T); // ignored
        BrefSnapshot s = new BrefSnapshot(owner("12"), List.of(shop()),
                List.of(noId, badUser, badRole, badShop, badHash, first, dup, deleted), SourceKind.EXPORT);
        assertThat(BrefSnapshotValidator.validate(s, Map.of(), Set.of(), VALID))
                .extracting(BlockedCase::code, BlockedCase::sourceId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PHONE_INVALID", s.owner().id()),
                        org.assertj.core.groups.Tuple.tuple("NO_IDENTIFIER", noId.id()),
                        org.assertj.core.groups.Tuple.tuple("USERNAME_INVALID", badUser.id()),
                        org.assertj.core.groups.Tuple.tuple("ROLE_UNKNOWN", badRole.id()),
                        org.assertj.core.groups.Tuple.tuple("MEMBER_SHOP_UNKNOWN", badShop.id()),
                        org.assertj.core.groups.Tuple.tuple("PASSWORD_UNSUPPORTED", badHash.id()),
                        org.assertj.core.groups.Tuple.tuple("DUPLICATE_IDENTIFIER", dup.id()));
    }

    @Test
    @DisplayName("conflicts: another account → ACCOUNT_EXISTS; same id not imported by this tool → ACCOUNT_ID_TAKEN")
    void conflicts() {
        BrefAccount owner = owner("+237600000001");
        BrefAccount member = member("seller", "vendeur");
        BrefSnapshot s = new BrefSnapshot(owner, List.of(shop()), List.of(member), SourceKind.DATABASE);
        assertThat(codes(s, Map.of(owner.id(), EnumSet.of(ConflictKind.PHONE)), Set.of()))
                .containsExactly("ACCOUNT_EXISTS");
        assertThat(codes(s, Map.of(member.id(), EnumSet.of(ConflictKind.SAME_ID)), Set.of()))
                .containsExactly("ACCOUNT_ID_TAKEN");
        // a previous run imported it: its own row is expected
        assertThat(codes(s, Map.of(member.id(), EnumSet.of(ConflictKind.SAME_ID)), Set.of(member.id()))).isEmpty();
        // the store rules' own message completes the label
        List<BlockedCase> shopCase = BrefSnapshotValidator.validate(s, Map.of(), Set.of(),
                sh -> Optional.of("Catégorie de boutique inconnue"));
        assertThat(shopCase).singleElement().satisfies(c -> {
            assertThat(c.code()).isEqualTo("SHOP_INVALID");
            assertThat(c.label()).isEqualTo("Boutique invalide : Catégorie de boutique inconnue");
        });
    }

    @Test
    @DisplayName("mappings: roles, sector, phone, legacy account (verification dates), employee names")
    void mappings() {
        assertThat(BrefMappings.memberRole("admin")).contains(Role.MANAGER);
        assertThat(BrefMappings.memberRole("seller")).contains(Role.EMPLOYEE);
        assertThat(BrefMappings.memberRole("viewer")).contains(Role.VIEWER);
        assertThat(BrefMappings.memberRole("owner")).isEmpty();
        assertThat(BrefMappings.sector("telecom")).isEqualTo("ELECTRONICS");
        assertThat(BrefMappings.sector("beauty")).isEqualTo("OTHER");
        assertThat(BrefMappings.sector(null)).isNull();
        assertThat(BrefMappings.e164("677 00 01 11")).isEqualTo("+237677000111");
        assertThat(BrefMappings.invalidPhone("abc")).isTrue();
        assertThat(BrefMappings.invalidPhone(" ")).isFalse();

        BrefAccount a = new BrefAccount(UUID.randomUUID(), "seller", " Mail@Example.test ", true, "+237677000111",
                false, " vendeur ", HASH, " ", null, shopId.toString(), true, false, null, null, T, T.plusSeconds(5),
                null);
        var legacy = BrefMappings.toLegacy(a);
        assertThat(legacy.email()).isEqualTo("Mail@Example.test");
        assertThat(legacy.username()).isEqualTo("vendeur");
        assertThat(legacy.firstName()).isNull();
        assertThat(legacy.emailVerifiedAt()).isEqualTo(T.plusSeconds(5));
        assertThat(legacy.phoneVerifiedAt()).isNull();
        assertThat(legacy.passwordHash()).isEqualTo(HASH);
        assertThat(legacy.toString()).doesNotContain("Mail", HASH);
        assertThat(BrefMappings.employeeFirstName(a)).isEqualTo("vendeur");
        assertThat(BrefMappings.employeeLastName(a)).isEmpty();
        assertThat(a.toString()).doesNotContain("Mail", HASH, "+237");
    }

    @Test
    @DisplayName("an export shop of another owner, or a row given twice, blocks; a numeric username clashes with a "
            + "phone of the snapshot; the clashing identifiers are named")
    void exportIntegrity() {
        BrefAccount owner = owner("+237677000111");
        BrefShop foreign = new BrefShop(UUID.randomUUID(), UUID.randomUUID(), "Autre", "A", "food", null, null, null,
                null, null, "CM", true, T, T, null, null);
        BrefAccount numeric = member("seller", "677000111"); // reads as the owner's phone
        BrefAccount twice = member("seller", "deux");
        BrefSnapshot s = new BrefSnapshot(owner, List.of(shop(), foreign), List.of(numeric, twice, twice),
                SourceKind.EXPORT);
        assertThat(BrefSnapshotValidator.validate(s, Map.of(), Set.of(), VALID))
                .extracting(BlockedCase::code, BlockedCase::sourceId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SHOP_INVALID", foreign.id()),
                        org.assertj.core.groups.Tuple.tuple("DUPLICATE_IDENTIFIER", numeric.id()),
                        org.assertj.core.groups.Tuple.tuple("DUPLICATE_IDENTIFIER", twice.id()));
        BrefSnapshot clean = new BrefSnapshot(owner, List.of(shop()), List.of(), SourceKind.EXPORT);
        assertThat(BrefSnapshotValidator.validate(clean,
                Map.of(owner.id(), EnumSet.of(ConflictKind.EMAIL, ConflictKind.PHONE)), Set.of(), VALID))
                .singleElement().satisfies(c -> assertThat(c.label()).endsWith(" : téléphone, e-mail"));
        assertThat(BrefMappings.usernameAsPhone("677000111")).isEqualTo("+237677000111");
        assertThat(BrefMappings.usernameAsPhone("vendeur1")).isNull();
    }

    @Test
    @DisplayName("checksum: SHA-256 hex of the canonical row, stable, and the shop's embedded logo is not part of it")
    void checksum() {
        BrefShop withLogo = new BrefShop(shopId, UUID.randomUUID(), "B", "A", "food", null, null, null, null, null,
                "CM", true, T, T, null, new byte[]{1, 2});
        BrefShop without = new BrefShop(shopId, withLogo.ownerId(), "B", "A", "food", null, null, null, null, null,
                "CM", true, T, T, null, null);
        assertThat(withLogo).isEqualTo(without);
        assertThat(Canonical.sha256(withLogo.canonical())).matches("[0-9a-f]{64}")
                .isEqualTo(Canonical.sha256(without.canonical()));
        assertThat(Canonical.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
