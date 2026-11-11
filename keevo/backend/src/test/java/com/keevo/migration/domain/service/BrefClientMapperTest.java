package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.ImportNote.Kind;
import com.keevo.migration.domain.service.BrefClientMapper.MappedClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("24.3 — Bref customer → Keevo client (pure, ADR-08)")
class BrefClientMapperTest {

    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-11");
    private static final UUID SHOP = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private static BrefCustomer c(String name, String phone, String email, String type, String nui, String address,
                                  String website, String birth, UUID consentBy) {
        return new BrefCustomer(UUID.randomUUID(), SHOP.toString(), name, phone, email, type, nui, null, address,
                null, website, birth, "Note", 3, new BigDecimal("1000.50"), T, consentBy == null ? null : T,
                consentBy, null, T, T.plusSeconds(1));
    }

    @Test
    @DisplayName("a clean customer: same id, its shop's store, E.164 phone, type, birth date, consent kept")
    void clean() {
        BrefCustomer src = c("Awa", "699000111", "awa@example.test", "Person", "M01", "Douala", "https://x.test",
                "1990-05-01", OWNER);
        MappedClient m = BrefClientMapper.map(src, Set.of(OWNER), TODAY);
        assertThat(m.id()).isEqualTo(src.id());
        assertThat(m.storeId()).isEqualTo(SHOP);
        assertThat(m.phone()).isEqualTo("+237699000111");
        assertThat(m.clientType()).isEqualTo("PERSON");
        assertThat(m.birthDate()).isEqualTo(LocalDate.parse("1990-05-01"));
        assertThat(m.marketingConsentBy()).isEqualTo(OWNER);
        assertThat(m.marketingConsentAt()).isEqualTo(T);
        assertThat(m.notes()).isEqualTo("Note");
        assertThat(m.createdAt()).isEqualTo(T);
        assertThat(m.rowNotes()).isEmpty();
    }

    @Test
    @DisplayName("blank name → phone; bad e-mail, future or malformed birth date, unknown type dropped; long fields cut")
    void notes() {
        MappedClient m = BrefClientMapper.map(c(" ", "+237699000111", "sans-arobase", "robot", "N".repeat(60),
                "A".repeat(300), "W".repeat(201), "2027-01-01", UUID.randomUUID()), Set.of(OWNER), TODAY);
        assertThat(m.name()).isEqualTo("+237699000111");
        assertThat(m.email()).isNull();
        assertThat(m.clientType()).isNull();
        assertThat(m.birthDate()).isNull();
        assertThat(m.marketingConsentBy()).isNull();
        assertThat(m.niu()).hasSize(50);
        assertThat(m.address()).hasSize(255);
        assertThat(m.website()).hasSize(200);
        assertThat(m.rowNotes()).containsExactlyInAnyOrder(Kind.CLIENT_NAME_MISSING, Kind.CLIENT_FIELD_DROPPED,
                Kind.CLIENT_FIELD_SHORTENED, Kind.CLIENT_CONSENT_AUTHOR_UNKNOWN);
        assertThat(BrefClientMapper.map(c("B", "+237699000111", null, null, null, null, null, "01/05/1990", null),
                Set.of(), TODAY).rowNotes()).containsExactly(Kind.CLIENT_FIELD_DROPPED);
        assertThat(BrefClientMapper.map(c("N".repeat(250), "+237699000111", null, "company", null, null, null, null,
                null), Set.of(), TODAY).name()).hasSize(200);
    }

    @Test
    @DisplayName("an unreadable phone is refused (the validator blocks it before any write)")
    void badPhone() {
        assertThatThrownBy(() -> BrefClientMapper.map(c("A", "abc", null, null, null, null, null, null, null),
                Set.of(), TODAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("today is taken in the most advanced time zone")
    void today() {
        assertThat(BrefClientMapper.today(Instant.parse("2026-11-10T11:00:00Z"))).isEqualTo(TODAY);
    }
}
