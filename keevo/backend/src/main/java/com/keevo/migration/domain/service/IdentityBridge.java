package com.keevo.migration.domain.service;

import com.keevo.identity.api.LegacyIdentityImportPort.AccountMatch;
import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BlockedCase.Kind;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.IdentityResolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;

/**
 * Story 24.7 — the identity bridge (R2: one person, one Keevo account): decides, for one Bref account and the Keevo
 * accounts holding its identifiers, whether the import creates an account, keeps an existing one, invites it (19.6b)
 * or blocks the owner. Pure function, O(matches) — at most a handful per account.
 *
 * <p>Rules (decisions D1–D5 of the 24.7 spec):
 * <ol>
 *   <li>The Keevo account is found by the E.164 phone, then by the email. A phone and an email held by two different
 *       Keevo accounts block ({@code IDENTITY_AMBIGUOUS}).</li>
 *   <li>Every other identifier must not lead to another account: the Bref username held by another account
 *       ({@code USERNAME_TAKEN}) or equal to another account's phone ({@code USERNAME_IS_PHONE}), the phone used as a
 *       username ({@code ACCOUNT_EXISTS}), the Google / Apple identity linked to another account
 *       ({@code SOCIAL_TAKEN}).</li>
 *   <li>A match by email is proven only when the email is verified on both sides; a match by phone when Bref
 *       confirmed the phone ({@code isVerified}) or the same account's email is proven.</li>
 *   <li>Owner: proven → LINK (the Keevo account, password and identifiers are kept; an OWNER membership is added);
 *       unproven → {@code IDENTITY_UNPROVEN}.</li>
 *   <li>Member: proven → LINK; phone match unproven → INVITE (19.6b consent), or SKIP when deactivated in Bref; email
 *       match unproven → the account is created without that email when another identifier remains.</li>
 *   <li>The Keevo account must be active and not the platform administrator ({@code LINK_TARGET_UNAVAILABLE}).</li>
 * </ol>
 */
public final class IdentityBridge {

    private IdentityBridge() {
    }

    /**
     * @param owner   whether {@code a} is the Bref owner (never invited)
     * @param matches the Keevo accounts holding one of its identifiers (empty = none)
     */
    public static IdentityResolution resolve(BrefAccount a, boolean owner, List<AccountMatch> matches) {
        Objects.requireNonNull(a, "a");
        List<AccountMatch> found = matches == null ? List.of() : matches;
        List<BlockedCase> cases = new ArrayList<>();
        if (found.stream().anyMatch(m -> m.kind() == ConflictKind.SAME_ID)) {
            // another Keevo account carries the Bref id (the caller already recognised the accounts it imported)
            return IdentityResolution.block(List.of(BlockedCase.of(Kind.ACCOUNT_ID_TAKEN, BREF_USER, a.id())));
        }
        AccountMatch phone = first(found, ConflictKind.PHONE);
        AccountMatch email = first(found, ConflictKind.EMAIL);
        boolean emailProven = email != null && a.emailVerified() && email.emailVerified();
        // a member's unproven email (typed by the owner) on another account is no evidence: it is not carried anyway
        // when the member is linked or invited by his phone
        if (phone != null && email != null && !phone.userId().equals(email.userId()) && (owner || emailProven)) {
            cases.add(BlockedCase.of(Kind.IDENTITY_AMBIGUOUS, "le téléphone et l'e-mail appartiennent à deux comptes "
                    + "Keevo différents", BREF_USER, a.id()));
        }
        AccountMatch target = phone != null ? phone : email;
        boolean dropEmail = false;
        if (phone == null && email != null && !emailProven) {
            boolean otherIdentifier = BrefMappings.e164(a.phone()) != null
                    || BrefMappings.trimmed(a.username()) != null;
            if (!owner && otherIdentifier) {
                dropEmail = true; // a member's email typed by the owner, never proven: not a reason to link
                target = null;
            } else {
                cases.add(BlockedCase.of(Kind.IDENTITY_UNPROVEN, "e-mail non vérifié", BREF_USER, a.id()));
            }
        }
        UUID targetId = target == null ? null : target.userId();
        for (AccountMatch m : found) {
            if (Objects.equals(m.userId(), targetId)) {
                continue;
            }
            switch (m.kind()) {
                case USERNAME -> cases.add(BlockedCase.of(Kind.USERNAME_TAKEN, BREF_USER, a.id()));
                case USERNAME_IS_PHONE -> cases.add(BlockedCase.of(Kind.USERNAME_IS_PHONE, BREF_USER, a.id()));
                case PHONE_IS_USERNAME -> cases.add(BlockedCase.of(Kind.ACCOUNT_EXISTS,
                        "téléphone utilisé comme identifiant de connexion", BREF_USER, a.id()));
                case SOCIAL -> cases.add(BlockedCase.of(Kind.SOCIAL_TAKEN, BREF_USER, a.id()));
                default -> {
                    // PHONE / EMAIL: handled above; SAME_ID: returned above
                }
            }
        }
        if (!cases.isEmpty()) {
            return IdentityResolution.block(distinct(cases));
        }
        if (target == null) {
            return IdentityResolution.create(dropEmail);
        }
        boolean proven = phone != null
                ? a.verified() || (emailProven && email.userId().equals(phone.userId()))
                : emailProven;
        if (!owner && !proven && !a.active()) {
            return IdentityResolution.skip(targetId); // nothing is written for him: the Keevo account's state is moot
        }
        if (!target.active() || target.platformAdmin()) {
            return IdentityResolution.block(List.of(BlockedCase.of(Kind.LINK_TARGET_UNAVAILABLE, BREF_USER, a.id())));
        }
        if (proven) {
            return IdentityResolution.link(targetId);
        }
        if (owner) {
            return IdentityResolution.block(List.of(BlockedCase.of(Kind.IDENTITY_UNPROVEN,
                    "téléphone non confirmé dans Bref", BREF_USER, a.id())));
        }
        return IdentityResolution.invite(targetId);
    }

    private static AccountMatch first(List<AccountMatch> matches, ConflictKind kind) {
        return matches.stream().filter(m -> m.kind() == kind && m.userId() != null).findFirst().orElse(null);
    }

    /** One case per code (a username held by two accounts is one problem). */
    private static List<BlockedCase> distinct(List<BlockedCase> cases) {
        List<BlockedCase> out = new ArrayList<>();
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (BlockedCase c : cases) {
            if (codes.add(c.code() + "|" + c.label())) {
                out.add(c);
            }
        }
        return out;
    }
}
