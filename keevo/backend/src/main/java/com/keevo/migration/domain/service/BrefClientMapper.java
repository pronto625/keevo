package com.keevo.migration.domain.service;

import com.keevo.migration.domain.model.BrefCustomer;
import com.keevo.migration.domain.model.ImportNote.Kind;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.3 — a Bref customer as a Keevo client (pure function, ADR-08): same id, the store of its shop (no merge
 * across stores), E.164 phone (the validator blocks an unreadable one: a Keevo client without it cannot be read
 * back), Bref fields within the limits of the client rules ({@code ClientProfileRules}: NIU / RCCM 50, address 255,
 * contact 120, website 200; name 200, e-mail 255), consent copied (its author kept only when it is an account of this
 * snapshot). A value that does not fit is cut or dropped with a note — never silently (decision D5).
 */
public final class BrefClientMapper {

    public static final int MAX_NAME = 200;
    public static final int MAX_EMAIL = 255;
    public static final int MAX_NIU = 50;
    public static final int MAX_RCCM = 50;
    public static final int MAX_ADDRESS = 255;
    public static final int MAX_CONTACT_NAME = 120;
    public static final int MAX_WEBSITE = 200;

    private BrefClientMapper() {
    }

    /**
     * A client to import.
     *
     * @param clientType {@code PERSON} | {@code COMPANY} | {@code null}
     */
    public record MappedClient(UUID id, UUID storeId, String name, String phone, String email, String notes,
                               String clientType, String niu, String rccm, String address, String contactName,
                               String website, LocalDate birthDate, Instant marketingConsentAt,
                               UUID marketingConsentBy, Instant marketingOptOutAt, Instant createdAt,
                               Instant updatedAt, List<Kind> rowNotes) {
    }

    /**
     * @param accountIds ids of the owner and members of the snapshot (consent authors that exist in Keevo after the
     *                   import)
     * @param today      today's date in the most advanced time zone (a birth date after it is in the future)
     */
    public static MappedClient map(BrefCustomer c, Set<UUID> accountIds, LocalDate today) {
        List<Kind> notes = new ArrayList<>();
        String phone = BrefMappings.e164(c.phone());
        if (phone == null) {
            throw new IllegalArgumentException("customer phone not E.164"); // blocked by the validator before
        }
        String name = BrefMappings.trimmed(c.name());
        if (name == null) {
            name = phone;
            notes.add(Kind.CLIENT_NAME_MISSING);
        } else {
            name = shorten(name, MAX_NAME, notes);
        }
        String email = BrefMappings.trimmed(c.email());
        if (email != null && (email.codePointCount(0, email.length()) > MAX_EMAIL || !email.contains("@"))) {
            email = null;
            drop(notes);
        }
        String type = null;
        String rawType = BrefMappings.trimmed(c.type());
        if (rawType != null) {
            type = switch (rawType.toLowerCase(Locale.ROOT)) {
                case "person" -> "PERSON";
                case "company" -> "COMPANY";
                default -> {
                    drop(notes);
                    yield null;
                }
            };
        }
        LocalDate birth = birthDate(c.birthDate(), today, notes);
        UUID consentBy = c.marketingConsentAt() == null ? null : c.marketingConsentById(); // no author without consent
        if (consentBy != null && !accountIds.contains(consentBy)) {
            consentBy = null;
            notes.add(Kind.CLIENT_CONSENT_AUTHOR_UNKNOWN);
        }
        UUID storeId = BrefMappings.uuidOrNull(c.shopId());
        if (storeId == null) {
            throw new IllegalArgumentException("customer shop unreadable"); // blocked by the validator before
        }
        return new MappedClient(c.id(), storeId, name, phone, email, BrefMappings.trimmed(c.notes()), type,
                shorten(c.nui(), MAX_NIU, notes), shorten(c.rccm(), MAX_RCCM, notes),
                shorten(c.address(), MAX_ADDRESS, notes), shorten(c.contactName(), MAX_CONTACT_NAME, notes),
                shorten(c.website(), MAX_WEBSITE, notes), birth, c.marketingConsentAt(), consentBy,
                c.marketingOptOutAt(), c.createdAt(), c.updatedAt(), List.copyOf(notes));
    }

    /** Today in the most advanced time zone (UTC+14), as the client rules use it. */
    public static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.ofHours(14));
    }

    private static LocalDate birthDate(String raw, LocalDate today, List<Kind> notes) {
        String v = BrefMappings.trimmed(raw);
        if (v == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(v);
            if (!date.isAfter(today)) {
                return date;
            }
        } catch (DateTimeParseException e) {
            // dropped below
        }
        drop(notes);
        return null;
    }

    private static String shorten(String raw, int max, List<Kind> notes) {
        String v = BrefMappings.trimmed(raw);
        if (v == null || v.codePointCount(0, v.length()) <= max) {
            return v;
        }
        if (!notes.contains(Kind.CLIENT_FIELD_SHORTENED)) {
            notes.add(Kind.CLIENT_FIELD_SHORTENED);
        }
        return BrefMappings.cut(v, max).strip();
    }

    private static void drop(List<Kind> notes) {
        if (!notes.contains(Kind.CLIENT_FIELD_DROPPED)) {
            notes.add(Kind.CLIENT_FIELD_DROPPED);
        }
    }
}
