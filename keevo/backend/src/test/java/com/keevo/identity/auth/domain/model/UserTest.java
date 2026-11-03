package com.keevo.identity.auth.domain.model;

import com.keevo.identity.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 19.1 — the domain user accepts a missing phone / password and carries the new profile fields. */
@DisplayName("User (19.1 — multiple identifiers)")
class UserTest {

    private static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");

    @Test
    @DisplayName("email-only user without password builds; hasPhoneNumber / hasPassword are false")
    void emailOnly_builds() {
        User u = new User(UUID.randomUUID(), null, null, Role.OWNER, true, T0, 0, null,
                "awa@example.cm", null, "Awa", "Ndiaye", T0, null);

        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.getPasswordHash()).isNull();
        assertThat(u.hasPhoneNumber()).isFalse();
        assertThat(u.hasPassword()).isFalse();
        assertThat(u.getEmail()).isEqualTo("awa@example.cm");
        assertThat(u.getFirstName()).isEqualTo("Awa");
        assertThat(u.getLastName()).isEqualTo("Ndiaye");
        assertThat(u.getEmailVerifiedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("legacy constructors and factories unchanged: new fields null")
    void legacyConstructors_newFieldsNull() {
        User owner = User.newOwner("+237690000001", "hash");
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        assertThat(owner.hasPhoneNumber()).isTrue();
        assertThat(owner.hasPassword()).isTrue();
        assertThat(owner.getEmail()).isNull();
        assertThat(owner.getUsername()).isNull();
        assertThat(owner.getFirstName()).isNull();
        assertThat(owner.getPhoneVerifiedAt()).isNull();
        assertThat(User.newEmployee("+237690000002", "hash").getRole()).isEqualTo(Role.EMPLOYEE);
    }

    @Test
    @DisplayName("every copy keeps the identifiers and profile")
    void copies_keepEveryField() {
        User base = new User(UUID.randomUUID(), "+237690000003", "hash", Role.OWNER, true, T0, 0, null,
                "a@x.cm", "awa", "Awa", "Ndiaye", T0, T0.plusSeconds(1));

        for (User c : new User[] {base.withLockoutState(2, T0), base.withPasswordHash("h2"),
                base.withPhoneNumber("+237690000003"), base.withEmail("A@X.cm"), base.withRole(Role.EMPLOYEE)}) {
            assertThat(c.getPhoneNumber()).isEqualTo("+237690000003");
            assertThat(c.getEmail()).isEqualToIgnoringCase("a@x.cm");
            assertThat(c.getUsername()).isEqualTo("awa");
            assertThat(c.getFirstName()).isEqualTo("Awa");
            assertThat(c.getLastName()).isEqualTo("Ndiaye");
            assertThat(c.getEmailVerifiedAt()).isEqualTo(T0);
            assertThat(c.getPhoneVerifiedAt()).isEqualTo(T0.plusSeconds(1));
        }
        User edited = base.withUsername("awa2").withNames("A", "N").withEmailVerifiedAt(null).withPhoneVerifiedAt(T0);
        assertThat(edited.getUsername()).isEqualTo("awa2");
        assertThat(edited.getFirstName()).isEqualTo("A");
        assertThat(edited.getLastName()).isEqualTo("N");
        assertThat(edited.getEmailVerifiedAt()).isNull();
        assertThat(edited.getPhoneVerifiedAt()).isEqualTo(T0);
        assertThat(edited.getPhoneNumber()).isEqualTo("+237690000003");
        assertThat(edited.getPasswordHash()).isEqualTo("hash");
    }

    @Test
    @DisplayName("a different phone or email is no longer verified")
    void changedIdentifier_clearsVerification() {
        User base = new User(UUID.randomUUID(), "+237690000003", "hash", Role.OWNER, true, T0, 0, null,
                "a@x.cm", null, null, null, T0, T0);

        User newPhone = base.withPhoneNumber("+237690000009");
        assertThat(newPhone.getPhoneNumber()).isEqualTo("+237690000009");
        assertThat(newPhone.getPhoneVerifiedAt()).isNull();
        assertThat(newPhone.getEmailVerifiedAt()).isEqualTo(T0);

        User newEmail = base.withEmail("b@x.cm");
        assertThat(newEmail.getEmail()).isEqualTo("b@x.cm");
        assertThat(newEmail.getEmailVerifiedAt()).isNull();
        assertThat(newEmail.getPhoneVerifiedAt()).isEqualTo(T0);

        assertThat(base.withEmail(null).getEmailVerifiedAt()).isNull();
        assertThat(base.withPhoneNumber("  ").getPhoneNumber()).isNull();
    }

    @Test
    @DisplayName("identifiers are stored stripped; blank ones as null (constructor and copies)")
    void identifiers_strippedBlankAsNull() {
        User u = new User(UUID.randomUUID(), "   ", null, Role.OWNER, true, T0, 0, null,
                "  awa@x.cm\t", " \n", null, null, null, null);
        assertThat(u.getPhoneNumber()).isNull();
        assertThat(u.getEmail()).isEqualTo("awa@x.cm");
        assertThat(u.getUsername()).isNull();
        assertThat(u.hasPhoneNumber()).isFalse();

        assertThat(u.withUsername("  awa.n ").getUsername()).isEqualTo("awa.n");
        assertThat(u.withEmail(" ").getEmail()).isNull();
        assertThat(u.withPhoneNumber(" +237690000001 ").getPhoneNumber()).isEqualTo("+237690000001");
        assertThat(User.normalizeIdentifier("")).isNull();
        assertThat(User.normalizeIdentifier(null)).isNull();
    }

    @Test
    @DisplayName("id, role and createdAt stay mandatory")
    void mandatoryFields() {
        assertThatThrownBy(() -> new User(null, null, null, Role.OWNER, true, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new User(UUID.randomUUID(), null, null, null, true, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new User(UUID.randomUUID(), null, null, Role.OWNER, true, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString prints no personal data (phone, email, username)")
    void toString_noPersonalData() {
        User u = new User(UUID.randomUUID(), "+237690000005", "hash", Role.OWNER, true, T0, 0, null,
                "secret@x.cm", "secretname", null, null, null, null);
        assertThat(u.toString()).doesNotContain("+237690000005", "secret@x.cm", "secretname", "hash");
    }
}
