package com.keevo.crm.contact.application.service;

import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.contact.domain.entity.ClientProfile;
import com.keevo.crm.contact.domain.entity.ClientType;
import com.keevo.shared.domain.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ClientProfileRules — Bref client fields (Story 20.2)")
class ClientProfileRulesTest {

    private static ClientProfileInput in(String type, String niu, String rccm, String address, String contact,
                                         String website, String birth) {
        return new ClientProfileInput(type, niu, rccm, address, contact, website, birth);
    }

    @Test
    void create_trimsAndParses() {
        ClientProfile p = ClientProfileRules.forCreate(
                in(" company ", " M012 ", "RC/DLA", " Akwa ", "Awa", "https://x.cm", "1990-05-04"));
        assertThat(p).isEqualTo(new ClientProfile(ClientType.COMPANY, "M012", "RC/DLA", "Akwa", "Awa",
                "https://x.cm", LocalDate.of(1990, 5, 4)));
        assertThat(ClientProfileRules.forCreate(null)).isEqualTo(ClientProfile.EMPTY);
        assertThat(ClientProfileRules.forCreate(in("", " ", null, "", null, null, ""))).isEqualTo(ClientProfile.EMPTY);
        assertThat(ClientProfileRules.forCreate(in("person", null, null, null, null, null, null)).type())
                .isEqualTo(ClientType.PERSON);
    }

    @Test
    void patch_absentKeeps_blankClears() {
        ClientProfile current = new ClientProfile(ClientType.PERSON, "N", "R", "A", "C", "W", LocalDate.of(2000, 1, 1));
        assertThat(ClientProfileRules.patch(current, ClientProfileInput.NONE)).isEqualTo(current);
        assertThat(ClientProfileRules.patch(current, null)).isEqualTo(current);
        assertThat(ClientProfileRules.patch(current, in("", "", null, null, null, null, "")))
                .isEqualTo(new ClientProfile(null, null, "R", "A", "C", "W", null));
        assertThat(ClientProfileRules.patch(current, in(null, null, null, "B", null, null, null)).address())
                .isEqualTo("B");
    }

    @Test
    void limitsAndFormats_frenchMessages() {
        assertInvalid(in("shop", null, null, null, null, null, null), "Type de client inconnu");
        assertInvalid(in(null, "x".repeat(51), null, null, null, null, null), "Le NIU ne doit pas dépasser 50");
        assertInvalid(in(null, null, "x".repeat(51), null, null, null, null), "Le RCCM ne doit pas dépasser 50");
        assertInvalid(in(null, null, null, "x".repeat(256), null, null, null), "L'adresse ne doit pas dépasser 255");
        assertInvalid(in(null, null, null, null, "x".repeat(121), null, null), "ne doit pas dépasser 120");
        assertInvalid(in(null, null, null, null, null, "x".repeat(201), null), "Le site web ne doit pas dépasser 200");
        assertInvalid(in(null, null, null, null, null, null, "04/05/1990"), "AAAA-MM-JJ");
        assertInvalid(in(null, null, null, null, null, null, LocalDate.now().plusDays(3).toString()),
                "ne peut pas être dans le futur");
        // exactly at the limit is accepted
        assertThat(ClientProfileRules.forCreate(in(null, "x".repeat(50), null, "y".repeat(255), null, null,
                LocalDate.now().toString())).niu()).hasSize(50);
    }

    private static void assertInvalid(ClientProfileInput input, String message) {
        assertThatThrownBy(() -> ClientProfileRules.forCreate(input))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException d = (DomainException) e;
                    assertThat(d.getDomainCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(d.getUserMessage()).contains(message);
                });
    }
}
