package com.keevo.migration.domain.service;

import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BlockedCase.Kind;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.BrefProduct;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.IdentityResolution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_CUSTOMER;
import static com.keevo.migration.domain.model.MigrationRecord.BREF_PRODUCT;
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
     * The 24.2 form, for callers that only know the kinds of conflict (no identity bridge): any identifier held by
     * another Keevo account blocks ({@code ACCOUNT_EXISTS}), as does an id taken by an account this tool did not
     * import ({@code ACCOUNT_ID_TAKEN}).
     *
     * @param conflicts       Keevo accounts already holding an identifier of the owner or of a member
     * @param importedUserIds Bref accounts this tool already imported (their {@code SAME_ID} is expected)
     */
    public static List<BlockedCase> validate(BrefSnapshot snapshot, Map<UUID, Set<ConflictKind>> conflicts,
                                             Set<UUID> importedUserIds, ShopRules shopRules) {
        Map<UUID, IdentityResolution> identities = new HashMap<>();
        List<BrefAccount> accounts = new ArrayList<>();
        accounts.add(snapshot.owner());
        snapshot.members().stream().filter(m -> !m.deleted()).forEach(accounts::add);
        for (BrefAccount a : accounts) {
            Set<ConflictKind> found = conflicts.getOrDefault(a.id(), Set.of());
            if (found.stream().anyMatch(k -> k != ConflictKind.SAME_ID)) {
                String which = String.join(", ", found.stream().filter(k -> k != ConflictKind.SAME_ID).sorted()
                        .map(BrefSnapshotValidator::conflictLabel).distinct().toList());
                identities.put(a.id(), IdentityResolution.block(List.of(BlockedCase.of(Kind.ACCOUNT_EXISTS, which,
                        BREF_USER, a.id()))));
            } else if (found.contains(ConflictKind.SAME_ID) && !importedUserIds.contains(a.id())) {
                identities.put(a.id(), IdentityResolution.block(List.of(BlockedCase.of(Kind.ACCOUNT_ID_TAKEN,
                        BREF_USER, a.id()))));
            }
        }
        return validate(snapshot, identities, shopRules);
    }

    /**
     * Story 24.7 — every blocking case, with the identity bridge's decision per account.
     *
     * @param identities Bref account id → the bridge's decision ({@link IdentityBridge}, or the account's migration
     *                   records on a re-run); an account absent from the map is created
     */
    public static List<BlockedCase> validate(BrefSnapshot snapshot, Map<UUID, IdentityResolution> identities,
                                             ShopRules shopRules) {
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
        Map<UUID, UUID> targets = new HashMap<>();
        account(owner, identities.get(owner.id()), seen, targets, cases);
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
            account(member, identities.get(member.id()), seen, targets, cases);
        }
        catalogue(snapshot, shopIds, cases);
        return cases;
    }

    /**
     * Story 24.3 — products and customers: each of one of the owner's shops and present once (an export must hold the
     * owner's own rows; the database adapter filters on the shops), a product price that fits a Keevo price, a
     * customer phone readable as E.164 (a Keevo client without it cannot be read back). O(n).
     */
    private static void catalogue(BrefSnapshot snapshot, Set<String> shopIds, List<BlockedCase> cases) {
        Set<UUID> productIds = new java.util.HashSet<>();
        for (BrefProduct p : snapshot.products()) {
            if (!productIds.add(p.id()) || !ownShop(p.shopId(), shopIds)) {
                cases.add(BlockedCase.of(Kind.PRODUCT_INVALID, "produit d'une autre boutique ou en double",
                        BREF_PRODUCT, p.id()));
            }
            if (!BrefCatalogPlanner.priceInRange(p.unitPrice())) {
                cases.add(BlockedCase.of(Kind.PRODUCT_INVALID, "prix négatif ou trop grand", BREF_PRODUCT, p.id()));
            }
        }
        Set<UUID> customerIds = new java.util.HashSet<>();
        for (BrefCustomer c : snapshot.customers()) {
            if (!customerIds.add(c.id()) || !ownShop(c.shopId(), shopIds)) {
                cases.add(BlockedCase.of(Kind.CLIENT_INVALID, "client d'une autre boutique ou en double",
                        BREF_CUSTOMER, c.id()));
            }
            if (BrefMappings.e164(c.phone()) == null) {
                cases.add(BlockedCase.of(Kind.CLIENT_INVALID, "téléphone invalide", BREF_CUSTOMER, c.id()));
            }
        }
    }

    private static boolean ownShop(String shopId, Set<String> shopIds) {
        UUID id = BrefMappings.uuidOrNull(shopId);
        return id != null && shopIds.contains(id.toString());
    }

    /**
     * One account: the Bref values a new account would carry (only when the account is created: a linked or invited
     * account keeps its Keevo values), the identifiers shared inside the snapshot, the bridge's cases, and two Bref
     * accounts that would land on the same Keevo account ({@code targets}: Keevo account → first Bref account).
     */
    private static void account(BrefAccount a, IdentityResolution identity, Identifiers seen, Map<UUID, UUID> targets,
                                List<BlockedCase> cases) {
        IdentityResolution resolution = identity == null ? IdentityResolution.create(false) : identity;
        String phone = BrefMappings.e164(a.phone());
        String email = BrefMappings.trimmed(a.email());
        String username = BrefMappings.trimmed(a.username());
        if (resolution.action() == IdentityResolution.Action.CREATE) {
            if (BrefMappings.invalidPhone(a.phone())) {
                cases.add(BlockedCase.of(Kind.PHONE_INVALID, BREF_USER, a.id()));
            }
            if (phone == null && email == null && username == null) {
                cases.add(BlockedCase.of(Kind.NO_IDENTIFIER, BREF_USER, a.id()));
            }
            if (email != null && email.length() > BrefMappings.MAX_EMAIL && !resolution.dropEmail()) {
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
        }
        String provider = BrefMappings.socialProvider(a);
        if (!seen.add(a.id(), phone, email, username,
                provider == null ? null : provider + ":" + BrefMappings.trimmed(a.socialId()))) {
            cases.add(BlockedCase.of(Kind.DUPLICATE_IDENTIFIER, BREF_USER, a.id()));
        }
        cases.addAll(resolution.cases());
        UUID keevo = resolution.existingAccount() ? resolution.keevoAccountId() : null; // SKIP writes nothing
        if (keevo != null && targets.putIfAbsent(keevo, a.id()) != null) {
            cases.add(BlockedCase.of(Kind.IDENTITY_AMBIGUOUS, "deux comptes Bref correspondent au même compte Keevo",
                    BREF_USER, a.id()));
        }
    }

    /** What clashes, in French, for the operator (never the value itself). */
    static String conflictLabel(ConflictKind kind) {
        return switch (kind) {
            case PHONE -> "téléphone";
            case EMAIL -> "e-mail";
            case USERNAME -> "identifiant";
            case USERNAME_IS_PHONE -> "identifiant égal à un téléphone";
            case PHONE_IS_USERNAME -> "téléphone utilisé comme identifiant";
            case SOCIAL -> "compte Google ou Apple";
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
        boolean add(UUID id, String phone, String email, String username, String social) {
            boolean unique = claim("social:" + social, social, id);
            unique &= claim("phone:" + phone, phone, id);
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
