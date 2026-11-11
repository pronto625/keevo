package com.keevo.identity.auth.domain.model;

import com.keevo.identity.api.Role;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User — Domain model for a registered user (global identity).
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
 * JPA mapping lives in {@code adapter/out/persistence/UserJpaEntity}.
 *
 * <p>Story 1.7: {@code tenantId} has been REMOVED from this model.
 * A user's tenant associations are now stored in {@code public.user_tenant_memberships}
 * via {@link UserTenantMembership}. A user can belong to N tenants.
 *
 * <p>Story 19.1: an account is identified by at least one of phone, email or username (enforced by the
 * {@code users_identifier_present} database constraint); phone and password hash are therefore nullable.
 * Email and username are unique case-insensitively; identifiers are stored stripped, a blank one as {@code null}. First/last name and verification dates are optional profile
 * data. Personal data (phone, email, username) is never printed by {@link #toString()}.
 */
public final class User {

    private final UUID id;
    private final String phoneNumber;
    private final String passwordHash;
    private final Role role;
    private final boolean active;
    private final Instant createdAt;
    private final int failedAttempts;
    private final Instant lockedUntil;
    // Story 19.1 — optional identifiers and profile
    private final String email;
    private final String username;
    private final String firstName;
    private final String lastName;
    private final Instant emailVerifiedAt;
    private final Instant phoneVerifiedAt;

    public User(UUID id, String phoneNumber, String passwordHash,
                Role role, boolean active, Instant createdAt) {
        this(id, phoneNumber, passwordHash, role, active, createdAt, 0, null);
    }

    public User(UUID id, String phoneNumber, String passwordHash,
                Role role, boolean active, Instant createdAt,
                int failedAttempts, Instant lockedUntil) {
        this(id, phoneNumber, passwordHash, role, active, createdAt, failedAttempts, lockedUntil,
             null, null, null, null, null, null);
    }

    /** Story 19.1 — full constructor; phone, password hash and every profile field may be null. */
    public User(UUID id, String phoneNumber, String passwordHash,
                Role role, boolean active, Instant createdAt,
                int failedAttempts, Instant lockedUntil,
                String email, String username, String firstName, String lastName,
                Instant emailVerifiedAt, Instant phoneVerifiedAt) {
        this.id              = Objects.requireNonNull(id,        "id must not be null");
        this.phoneNumber     = normalizeIdentifier(phoneNumber);
        this.passwordHash    = passwordHash;
        this.role            = Objects.requireNonNull(role,      "role must not be null");
        this.active          = active;
        this.createdAt       = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.failedAttempts  = failedAttempts;
        this.lockedUntil     = lockedUntil;
        this.email           = normalizeIdentifier(email);
        this.username        = normalizeIdentifier(username);
        this.firstName       = firstName;
        this.lastName        = lastName;
        this.emailVerifiedAt = emailVerifiedAt;
        this.phoneVerifiedAt = phoneVerifiedAt;
    }

    /**
     * Factory method for creating a new user at registration.
     *
     * <p>Story 1.7: tenantId parameter REMOVED — role/tenant association is handled
     * separately via {@link UserTenantMembership#create(UUID, UUID, String)}.
     */
    public static User newOwner(String phoneNumber, String passwordHash) {
        return new User(
            UUID.randomUUID(),
            phoneNumber,
            passwordHash,
            Role.OWNER,
            true,
            Instant.now(),
            0,
            null
        );
    }

    /**
     * Story 19.2 — account created by a successful sign-in code: OWNER (it creates its company next), no password,
     * no membership, identified by the phone or the email the code was sent to, which is verified at
     * {@code verifiedAt}.
     */
    public static User newOtpAccount(String phoneNumber, String email, Instant verifiedAt) {
        String phone = normalizeIdentifier(phoneNumber);
        String mail = normalizeIdentifier(email);
        return new User(UUID.randomUUID(), phone, null, Role.OWNER, true, Instant.now(), 0, null,
                mail, null, null, null,
                mail != null ? verifiedAt : null, phone != null ? verifiedAt : null);
    }

    /**
     * Story 19.3 — account created by a Google or Apple sign-in: OWNER (it creates its company next), no password,
     * no phone, no membership. Identified by the provider-verified email (verified at {@code verifiedAt}) or, when
     * the token carries none, by a technical username never shown to the user; the social identity itself lives in
     * {@code user_authenticators}.
     */
    public static User newSocialAccount(String email, String username, String firstName, String lastName,
                                        Instant verifiedAt) {
        String mail = normalizeIdentifier(email);
        return new User(UUID.randomUUID(), null, null, Role.OWNER, true, Instant.now(), 0, null,
                mail, normalizeIdentifier(username), normalizeIdentifier(firstName), normalizeIdentifier(lastName),
                mail != null ? verifiedAt : null, null);
    }

    /**
     * Factory method for creating an employee user account (Story 3.5).
     * Role is EMPLOYEE; active from creation.
     */
    public static User newEmployee(String phoneNumber, String passwordHash) {
        return newEmployee(phoneNumber, null, passwordHash);
    }

    /**
     * Story 19.4 — employee account identified by a phone, a username, or both (at least one, checked by the caller
     * and by the {@code users_identifier_present} CHECK). The username is stored as typed (stripped).
     */
    public static User newEmployee(String phoneNumber, String username, String passwordHash) {
        return new User(UUID.randomUUID(), phoneNumber, passwordHash, Role.EMPLOYEE, true, Instant.now(), 0, null,
                null, username, null, null, null, null);
    }

    public UUID    getId()             { return id; }
    public String  getPhoneNumber()    { return phoneNumber; }
    public String  getPasswordHash()   { return passwordHash; }
    public Role    getRole()           { return role; }
    public boolean isActive()          { return active; }
    public Instant getCreatedAt()      { return createdAt; }
    /** Record-style accessor for failed login attempt count. */
    public int     failedAttempts()    { return failedAttempts; }
    /** Record-style accessor for account lockout expiry (null = not locked). */
    public Instant lockedUntil()       { return lockedUntil; }
    public String  getEmail()          { return email; }
    public String  getUsername()       { return username; }
    public String  getFirstName()      { return firstName; }
    public String  getLastName()       { return lastName; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getPhoneVerifiedAt() { return phoneVerifiedAt; }
    /** Story 19.1 — true when a non-blank phone number is present. */
    public boolean hasPhoneNumber()    { return phoneNumber != null && !phoneNumber.isBlank(); }
    /** Story 19.1 — true when a password hash is present (OTP/social-only accounts have none). */
    public boolean hasPassword()       { return passwordHash != null && !passwordHash.isBlank(); }

    /**
     * Story 19.6b — true when the person can recover the account alone: a phone (forgot-password and sign-in codes are
     * sent to it) or a verified email (sign-in codes by email). A username-only account depends on an owner.
     */
    public boolean hasRecoveryChannel() {
        return hasPhoneNumber() || (email != null && !email.isBlank() && emailVerifiedAt != null);
    }

    /** Returns new User instance with updated failedAttempts and lockedUntil. */
    public User withLockoutState(int newFailedAttempts, Instant newLockedUntil) {
        return copy().failedAttempts(newFailedAttempts).lockedUntil(newLockedUntil).build();
    }

    /** Returns new User instance with updated password hash (Story 3.5). */
    public User withPasswordHash(String newPasswordHash) {
        return copy().passwordHash(newPasswordHash).build();
    }

    /**
     * Story 14.11 — returns a copy with the given phone number. Story 19.1: a different phone is no longer
     * verified ({@code phoneVerifiedAt} cleared).
     */
    public User withPhoneNumber(String newPhoneNumber) {
        String normalized = normalizeIdentifier(newPhoneNumber);
        Copy c = copy().phoneNumber(normalized);
        if (!Objects.equals(phoneNumber, normalized)) {
            c.phoneVerifiedAt(null);
        }
        return c.build();
    }

    /** Story 14.11 — returns a copy with the given role. */
    public User withRole(Role newRole) {
        return copy().role(newRole).build();
    }

    /**
     * Story 19.1 — returns a copy with the given email (stripped, blank = none; unique case-insensitively in the
     * database). A different address is no longer verified ({@code emailVerifiedAt} cleared).
     */
    public User withEmail(String newEmail) {
        String normalized = normalizeIdentifier(newEmail);
        Copy c = copy().email(normalized);
        boolean same = email == null ? normalized == null : email.equalsIgnoreCase(normalized);
        if (!same) {
            c.emailVerifiedAt(null);
        }
        return c.build();
    }

    /** Story 19.1 — returns a copy with the given username (stripped, blank = none). */
    public User withUsername(String newUsername) {
        return copy().username(newUsername).build();
    }

    /** Story 19.1 — returns a copy with the given first and last name. */
    public User withNames(String newFirstName, String newLastName) {
        return copy().firstName(newFirstName).lastName(newLastName).build();
    }

    /** Story 19.1 — returns a copy with the given email verification date. */
    public User withEmailVerifiedAt(Instant newEmailVerifiedAt) {
        return copy().emailVerifiedAt(newEmailVerifiedAt).build();
    }

    /** Story 19.1 — returns a copy with the given phone verification date. */
    public User withPhoneVerifiedAt(Instant newPhoneVerifiedAt) {
        return copy().phoneVerifiedAt(newPhoneVerifiedAt).build();
    }

    /**
     * Story 19.1 — an identifier (phone, email, username) is stored stripped; a blank one is stored as
     * {@code null} so it never collides on the unique indexes nor counts as an identifier.
     */
    public static String normalizeIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private Copy copy() {
        return new Copy(this);
    }

    /** Mutable copy of every field — the single place the full constructor is called by the {@code withX} copies. */
    private static final class Copy {
        private final UUID id;
        private final Instant createdAt;
        private final boolean active;
        private String phoneNumber;
        private String passwordHash;
        private Role role;
        private int failedAttempts;
        private Instant lockedUntil;
        private String email;
        private String username;
        private String firstName;
        private String lastName;
        private Instant emailVerifiedAt;
        private Instant phoneVerifiedAt;

        Copy(User u) {
            id = u.id; createdAt = u.createdAt; active = u.active;
            phoneNumber = u.phoneNumber; passwordHash = u.passwordHash; role = u.role;
            failedAttempts = u.failedAttempts; lockedUntil = u.lockedUntil;
            email = u.email; username = u.username; firstName = u.firstName; lastName = u.lastName;
            emailVerifiedAt = u.emailVerifiedAt; phoneVerifiedAt = u.phoneVerifiedAt;
        }

        Copy phoneNumber(String v)       { phoneNumber = v; return this; }
        Copy passwordHash(String v)      { passwordHash = v; return this; }
        Copy role(Role v)                { role = v; return this; }
        Copy failedAttempts(int v)       { failedAttempts = v; return this; }
        Copy lockedUntil(Instant v)      { lockedUntil = v; return this; }
        Copy email(String v)             { email = v; return this; }
        Copy username(String v)          { username = v; return this; }
        Copy firstName(String v)         { firstName = v; return this; }
        Copy lastName(String v)          { lastName = v; return this; }
        Copy emailVerifiedAt(Instant v)  { emailVerifiedAt = v; return this; }
        Copy phoneVerifiedAt(Instant v)  { phoneVerifiedAt = v; return this; }

        User build() {
            return new User(id, phoneNumber, passwordHash, role, active, createdAt, failedAttempts, lockedUntil,
                            email, username, firstName, lastName, emailVerifiedAt, phoneVerifiedAt);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User u)) return false;
        return Objects.equals(id, u.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        // Story 19.1 — no personal data (phone, email, username) in logs.
        return "User{id=" + id + ", role=" + role + "}";
    }
}
