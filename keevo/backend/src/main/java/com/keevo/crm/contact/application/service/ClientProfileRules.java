package com.keevo.crm.contact.application.service;

import com.keevo.crm.api.ClientProfileInput;
import com.keevo.crm.contact.domain.entity.ClientProfile;
import com.keevo.crm.contact.domain.entity.ClientType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Story 20.2 — the single rule set of a client's Bref fields, shared by REST and sync so the two entry points cannot
 * diverge. Pure Java. Limits are Bref's ({@code UpdateCustomerDto}): NIU / RCCM 50, address 255, contact 120,
 * website 200; birth date ISO, never in the future. Any bad value → {@code VALIDATION_ERROR} with a French message.
 *
 * <p>Creation: {@code null} and blank both mean « none ». Update (PATCH convention of 20.1): {@code null} (absent)
 * keeps the stored value, a blank string clears it.
 */
public final class ClientProfileRules {

    public static final int MAX_NIU = 50;
    public static final int MAX_RCCM = 50;
    public static final int MAX_ADDRESS = 255;
    public static final int MAX_CONTACT_NAME = 120;
    public static final int MAX_WEBSITE = 200;

    private ClientProfileRules() {
    }

    /** The profile of a new client. */
    public static ClientProfile forCreate(ClientProfileInput raw) {
        return patch(ClientProfile.EMPTY, raw);
    }

    /** {@code current} with {@code raw} applied (validated here); {@code null} / empty input keeps it. */
    public static ClientProfile patch(ClientProfile current, ClientProfileInput raw) {
        if (current == null) current = ClientProfile.EMPTY;
        if (raw == null) return current;
        return new ClientProfile(
                raw.clientType() == null ? current.type() : type(raw.clientType()),
                pick(raw.niu(), current.niu(), MAX_NIU, "Le NIU ne doit pas dépasser " + MAX_NIU + " caractères"),
                pick(raw.rccm(), current.rccm(), MAX_RCCM,
                        "Le RCCM ne doit pas dépasser " + MAX_RCCM + " caractères"),
                pick(raw.address(), current.address(), MAX_ADDRESS,
                        "L'adresse ne doit pas dépasser " + MAX_ADDRESS + " caractères"),
                pick(raw.contactName(), current.contactName(), MAX_CONTACT_NAME,
                        "Le nom de la personne à contacter ne doit pas dépasser " + MAX_CONTACT_NAME + " caractères"),
                pick(raw.website(), current.website(), MAX_WEBSITE,
                        "Le site web ne doit pas dépasser " + MAX_WEBSITE + " caractères"),
                raw.birthDate() == null ? current.birthDate() : birthDate(raw.birthDate()));
    }

    private static ClientType type(String raw) {
        if (raw.isBlank()) return null;
        return ClientType.parse(raw).orElseThrow(() -> invalid("Type de client inconnu : particulier ou entreprise"));
    }

    private static String pick(String raw, String current, int max, String message) {
        if (raw == null) return current;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > max) throw invalid(message);
        return trimmed;
    }

    private static LocalDate birthDate(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;
        LocalDate date;
        try {
            date = LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw invalid("Date de naissance invalide : utilisez le format AAAA-MM-JJ");
        }
        // « today » in the most advanced time zone: a date that is already today somewhere is never refused
        if (date.isAfter(LocalDate.now(ZoneOffset.ofHours(14)))) {
            throw invalid("La date de naissance ne peut pas être dans le futur");
        }
        return date;
    }

    private static DomainException invalid(String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message);
    }
}
