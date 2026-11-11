package com.keevo.migration.domain.service;

import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BlockedCase.Kind;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_SHOP;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;

/**
 * Story 24.2 — every reason to block an owner, found before any write (pure function: the snapshot, the Keevo
 * conflicts, the accounts this tool already imported, the store rules). An owner with at least one case is not
 * imported at all. O(n) over the owner, shops and members (one hash map per identifier kind).
 */
public final class BrefSnapshotValidator {

    /** The store module's rules for a shop (name, Bref fields): the French problem, or empty when valid. */
    @FunctionalInterface
    public interface ShopRules {
        Optional<String> problem(BrefShop shop);
    }

    private static final int MAX_ADDRESS = 255;

    private BrefSnapshotValidator() {
    }

    /**
     * @param conflicts       Keevo accounts already holding an identifier of the owner or of a member
     * @param importedUserIds Bref accounts this tool already imported (their {@code SAME_ID} is expected)
     */
    public static List<BlockedCase> validate(BrefSnapshot snapshot, Map<UUID, Set<ConflictKind>> conflicts,
                                             Set<UUID> importedUserIds, ShopRules shopRules) {
        List<BlockedCase> cases = new ArrayList<>();
        BrefAccount owner = snapshot.owner();
        if (owner.deleted()) {
            cases.add(BlockedCase.of(Kind.OWNER_DELETED, BREF_USER, owner.id()));
        } else if (!owner.active()) {
            cases.add(BlockedCase.of(Kind.OWNER_INACTIVE, BREF_USER, owner.id()));
        }
        if (snapshot.shops().isEmpty()) {
            cases.add(BlockedCase.of(Kind.OWNER_NO_SHOP, BREF_USER, owner.id()));
        }
        Identifiers seen = new Identifiers();
        account(owner, conflicts, importedUserIds, seen, cases);
        Set<UUID> rowIds = new java.util.HashSet<>();
        rowIds.add(owner.id());
        for (BrefShop shop : snapshot.shops()) {
            if (!owner.id().equals(shop.ownerId()) || !rowIds.add(shop.id())) {
                // an export must hold the owner's own shops, each once (the database adapter filters on the owner)
                cases.add(BlockedCase.of(Kind.SHOP_INVALID, "boutique d'un autre compte ou en double", BREF_SHOP,
                        shop.id()));
            }
            String address = BrefMappings.trimmed(shop.address());
            if (address != null && address.length() > MAX_ADDRESS) {
                cases.add(BlockedCase.of(Kind.SHOP_INVALID, "adresse trop longue (255 caractères au plus)",
                        BREF_SHOP, shop.id()));
            }
            shopRules.problem(shop).ifPresent(p -> cases.add(BlockedCase.of(Kind.SHOP_INVALID, p, BREF_SHOP,
                    shop.id())));
        }
        Set<String> shopIds = new java.util.HashSet<>();
        snapshot.shops().forEach(s -> shopIds.add(s.id().toString()));
        for (BrefAccount member : snapshot.members()) {
            if (!rowIds.add(member.id())) {
                cases.add(BlockedCase.of(Kind.DUPLICATE_IDENTIFIER, "membre en double", BREF_USER, member.id()));
                continue;
            }
            if (member.deleted()) {
                continue; // not imported (counted)
            }
            if (BrefMappings.memberRole(member.role()).isEmpty()) {
                cases.add(BlockedCase.of(Kind.ROLE_UNKNOWN, BREF_USER, member.id()));
            }
            String shopId = BrefMappings.trimmed(member.shopId());
            if (shopId == null || !shopIds.contains(shopId.toLowerCase(Locale.ROOT))) {
                cases.add(BlockedCase.of(Kind.MEMBER_SHOP_UNKNOWN, BREF_USER, member.id()));
            }
            account(member, conflicts, importedUserIds, seen, cases);
        }
        return cases;
    }

    private static void account(BrefAccount a, Map<UUID, Set<ConflictKind>> conflicts, Set<UUID> imported,
                                Identifiers seen, List<BlockedCase> cases) {
        String phone = BrefMappings.e164(a.phone());
        String email = BrefMappings.trimmed(a.email());
        String username = BrefMappings.trimmed(a.username());
        if (BrefMappings.invalidPhone(a.phone())) {
            cases.add(BlockedCase.of(Kind.PHONE_INVALID, BREF_USER, a.id()));
        }
        if (phone == null && email == null && username == null) {
            cases.add(BlockedCase.of(Kind.NO_IDENTIFIER, BREF_USER, a.id()));
        }
        if (email != null && email.length() > BrefMappings.MAX_EMAIL) {
            cases.add(BlockedCase.of(Kind.EMAIL_INVALID, BREF_USER, a.id()));
        }
        if (username != null && !BrefMappings.validUsername(username)) {
            cases.add(BlockedCase.of(Kind.USERNAME_INVALID, BREF_USER, a.id()));
        }
        if (tooLong(a.firstName()) || tooLong(a.lastName())) {
            cases.add(BlockedCase.of(Kind.NAME_TOO_LONG, BREF_USER, a.id()));
        }
        if (!BrefMappings.supportedHash(BrefMappings.trimmed(a.passwordHash()))) {
            cases.add(BlockedCase.of(Kind.PASSWORD_UNSUPPORTED, BREF_USER, a.id()));
        }
        if (!seen.add(a.id(), phone, email, username)) {
            cases.add(BlockedCase.of(Kind.DUPLICATE_IDENTIFIER, BREF_USER, a.id()));
        }
        Set<ConflictKind> found = conflicts.getOrDefault(a.id(), Set.of());
        if (found.stream().anyMatch(k -> k != ConflictKind.SAME_ID)) {
            String which = String.join(", ", found.stream().filter(k -> k != ConflictKind.SAME_ID).sorted()
                    .map(BrefSnapshotValidator::conflictLabel).distinct().toList());
            cases.add(BlockedCase.of(Kind.ACCOUNT_EXISTS, which, BREF_USER, a.id()));
        } else if (found.contains(ConflictKind.SAME_ID) && !imported.contains(a.id())) {
            cases.add(BlockedCase.of(Kind.ACCOUNT_ID_TAKEN, BREF_USER, a.id()));
        }
    }

    /** What clashes, in French, for the operator (never the value itself). */
    static String conflictLabel(ConflictKind kind) {
        return switch (kind) {
            case PHONE -> "téléphone";
            case EMAIL -> "e-mail";
            case USERNAME -> "identifiant";
            case USERNAME_IS_PHONE -> "identifiant égal à un téléphone";
            case SAME_ID -> "identifiant interne";
        };
    }

    private static boolean tooLong(String name) {
        String n = BrefMappings.trimmed(name);
        return n != null && n.length() > BrefMappings.MAX_NAME;
    }

    /** Identifiers already used in the snapshot (Keevo compares email and username case-insensitively). */
    private static final class Identifiers {
        private final Map<String, UUID> used = new HashMap<>();

        /** @return false when one of the identifiers is already used by another account of the snapshot */
        boolean add(UUID id, String phone, String email, String username) {
            boolean unique = claim("phone:" + phone, phone, id);
            unique &= claim("email:" + lower(email), email, id);
            unique &= claim("username:" + lower(username), username, id);
            // a username that reads as another account's phone makes the sign-in ambiguous
            String usernamePhone = BrefMappings.usernameAsPhone(username);
            unique &= claim("phone:" + usernamePhone, usernamePhone, id);
            return unique;
        }

        private boolean claim(String key, String value, UUID id) {
            if (value == null) {
                return true;
            }
            UUID previous = used.putIfAbsent(key, id);
            return previous == null || previous.equals(id);
        }

        private static String lower(String value) {
            return value == null ? null : value.toLowerCase(Locale.ROOT);
        }
    }
}
