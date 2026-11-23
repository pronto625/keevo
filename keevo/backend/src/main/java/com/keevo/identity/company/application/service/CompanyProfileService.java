package com.keevo.identity.company.application.service;

import com.keevo.identity.company.domain.model.CompanyCategory;
import com.keevo.identity.company.domain.port.out.CompanyLogoNormalizer;
import com.keevo.identity.company.domain.port.out.CompanyLogoStorage;
import com.keevo.identity.company.domain.port.out.CompanyProfileRepository;
import com.keevo.identity.company.domain.port.out.CompanyProfileRepository.CompanyDetails;
import com.keevo.identity.company.domain.port.out.CompanyProfileRepository.DetailsPatch;
import com.keevo.identity.company.domain.port.out.CompanyProfileRepository.TenantIdentity;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.onboarding.CompanyProfileUpdatedEvent;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The company identity of the tenant (Story 14.2, UX-DR5): an optional designation and an optional logo, edited by
 * the owner and shown on invoices and export documents. Implements the core {@link CompanyIdentity} port so the
 * other modules ({@code reporting}, {@code invoicing}) depend on {@code shared} only.
 *
 * <p>Display rule: the designation when filled in, else the tenant name (the shop name typed at onboarding — a
 * legacy row whose name is still its technical code does not count), else « Mon commerce ».
 */
@Service
public class CompanyProfileService implements CompanyIdentity {

    /** Longest designation (column {@code public.tenants.designation varchar(150)}). */
    public static final int MAX_DESIGNATION = 150;

    private final CompanyProfileRepository repository;
    private final CompanyLogoStorage logos;
    private final CompanyLogoNormalizer normalizer;
    private final ApplicationEventPublisher events;
    private final int maxLogoBytes;
    private final Clock clock;

    @Autowired
    public CompanyProfileService(CompanyProfileRepository repository, CompanyLogoStorage logos,
                                 CompanyLogoNormalizer normalizer, ApplicationEventPublisher events,
                                 @Value("${keevo.company-logo.max-bytes:204800}") int maxLogoBytes, Clock clock) {
        this.repository = repository;
        this.logos = logos;
        this.normalizer = normalizer;
        this.events = events;
        this.maxLogoBytes = maxLogoBytes;
        this.clock = clock;
    }

    /**
     * What the settings screen shows: the designation typed, the name shown on documents, the shop name (tenant
     * name, {@code null} when it is still the technical code — shown when the designation is cleared), a logo or not.
     */
    public record CompanyProfile(String designation, String displayName, String shopName, boolean hasLogo) {
    }

    // ── CompanyIdentity (core port) ──────────────────────────────────────────────────────────────────────

    @Override
    public String displayName(String tenantSchema) {
        return repository.find(tenantSchema).map(CompanyProfileService::displayName).orElse(DEFAULT_NAME);
    }

    @Override
    public Optional<Logo> logo(String tenantSchema) {
        if (!TenantSchema.isValid(tenantSchema)) {
            return Optional.empty();
        }
        try {
            return logos.read(tenantSchema);
        } catch (IOException e) {
            throw new UncheckedIOException("Company logo could not be read", e);
        }
    }

    @Override
    public boolean hasLogo(String tenantSchema) {
        return TenantSchema.isValid(tenantSchema) && logos.exists(tenantSchema);
    }

    @Override
    public boolean isActive(String tenantSchema) {
        return repository.find(tenantSchema).map(t -> "ACTIVE".equals(t.status())).orElse(false);
    }

    /** The tenant name typed at onboarding, {@code null} when blank or still the technical code. */
    static String shopName(TenantIdentity t) {
        String name = trimToNull(t.name());
        return name != null && !name.equalsIgnoreCase(t.code()) ? name : null;
    }

    private CompanyProfile profile(TenantIdentity t, boolean hasLogo) {
        return new CompanyProfile(trimToNull(t.designation()), displayName(t), shopName(t), hasLogo);
    }

    static String displayName(TenantIdentity t) {
        String designation = trimToNull(t.designation());
        if (designation != null) {
            return designation;
        }
        String name = shopName(t);
        return name != null ? name : DEFAULT_NAME;
    }

    // ── owner settings ───────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CompanyProfile get(String tenantSchema) {
        TenantIdentity t = tenant(tenantSchema);
        return profile(t, logos.exists(tenantSchema));
    }

    /** Sets the designation; blank clears it (the shop name is shown again). */
    @Transactional
    public CompanyProfile updateDesignation(String tenantSchema, String designation, UUID actorId) {
        TenantIdentity t = tenant(tenantSchema);
        String value = trimToNull(designation);
        if (value != null && value.length() > MAX_DESIGNATION) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Designation too long",
                    Map.of("reason", "La désignation ne doit pas dépasser " + MAX_DESIGNATION + " caractères"));
        }
        String before = trimToNull(t.designation());
        if (!java.util.Objects.equals(before, value)) {
            if (!repository.updateDesignation(tenantSchema, value)) {
                throw new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found");
            }
            TenantIdentity updated = new TenantIdentity(t.id(), t.code(), t.name(), value, t.status());
            publish(t, updated, map("designation", before), map("designation", value), actorId, tenantSchema);
            t = updated;
        }
        return profile(t, logos.exists(tenantSchema));
    }

    // ── full profile (owner-edited identity + setup mark) ────────────────────────────────────────────────

    /**
     * The whole identity of the organisation: designation, description, activity category, address, phone, e-mail,
     * NIU, RCCM, country, whether a logo exists and whether the owner finished the sign-up steps.
     * Every field but {@code country} may be null; NIU and RCCM are never mandatory.
     */
    public record FullProfile(String designation, String displayName, String description, String category,
                              String address, String phone, String email, String niu, String rccm, String country,
                              boolean hasLogo, boolean setupCompleted) {
    }

    @Transactional(readOnly = true)
    public FullProfile getFullProfile(String tenantSchema) {
        TenantIdentity t = tenant(tenantSchema);
        return full(t, details(tenantSchema), logos.exists(tenantSchema));
    }

    /**
     * Partial update: a {@code null} field is left unchanged, a blank one is cleared (the {@code country} never).
     * Owner only (checked by the controller). A bad value → {@code VALIDATION_ERROR} and nothing is written.
     */
    @Transactional
    public FullProfile updateProfile(String tenantSchema, DetailsPatch input, UUID actorId) {
        TenantIdentity t = tenant(tenantSchema);
        CompanyDetails before = details(tenantSchema);
        DetailsPatch patch = validated(input);
        if (!repository.updateDetails(tenantSchema, patch)) {
            throw new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found");
        }
        TenantIdentity afterTenant = tenant(tenantSchema);
        CompanyDetails after = details(tenantSchema);
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff("designation", t.designation(), afterTenant.designation(), was, now);
        diff("description", before.description(), after.description(), was, now);
        diff("category", before.category(), after.category(), was, now);
        diff("address", before.address(), after.address(), was, now);
        diff("phone", before.phone(), after.phone(), was, now);
        diff("email", before.email(), after.email(), was, now);
        diff("niu", before.niu(), after.niu(), was, now);
        diff("rccm", before.rccm(), after.rccm(), was, now);
        diff("country", before.country(), after.country(), was, now);
        if (!now.isEmpty() || !was.isEmpty()) {
            publish(t, afterTenant, was, now, actorId, tenantSchema);
        }
        return full(afterTenant, after, logos.exists(tenantSchema));
    }

    /** The owner finished the sign-up steps (organisation, first store). Idempotent. */
    @Transactional
    public FullProfile completeSetup(String tenantSchema) {
        TenantIdentity t = tenant(tenantSchema);
        repository.markSetupCompleted(tenantSchema);
        return full(t, details(tenantSchema), logos.exists(tenantSchema));
    }

    private CompanyDetails details(String tenantSchema) {
        return repository.findDetails(tenantSchema)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found"));
    }

    private static FullProfile full(TenantIdentity t, CompanyDetails d, boolean hasLogo) {
        return new FullProfile(trimToNull(t.designation()), displayName(t), d.description(), d.category(), d.address(),
                d.phone(), d.email(), d.niu(), d.rccm(), d.country(), hasLogo, d.setupCompleted());
    }

    private static void diff(String key, String before, String after, Map<String, Object> was, Map<String, Object> now) {
        if (!java.util.Objects.equals(trimToNull(before), trimToNull(after))) {
            if (trimToNull(before) != null) {
                was.put(key, before);
            }
            if (trimToNull(after) != null) {
                now.put(key, after);
            }
        }
    }

    static final int MAX_DESCRIPTION = 1000;
    static final int MAX_ADDRESS = 255;
    static final int MAX_PHONE = 30;
    static final int MAX_EMAIL = 150;
    static final int MAX_NIU = 30;
    static final int MAX_RCCM = 50;
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Strips each given value (blank stays blank = clear) and rejects what the columns cannot hold. */
    static DetailsPatch validated(DetailsPatch p) {
        String designation = limited("designation", p.designation(), MAX_DESIGNATION);
        String description = limited("description", p.description(), MAX_DESCRIPTION);
        String category = stripKeepingNull(p.category());
        if (category != null && !category.isEmpty()) {
            // Story 22.19 — the key or its French label (as the message lists them)
            category = CompanyCategory.resolve(category).orElseThrow(() ->
                    invalid("category", "Catégorie inconnue (valeurs : " + CompanyCategory.accepted() + ")"));
        }
        String address = limited("address", p.address(), MAX_ADDRESS);
        String phone = limited("phone", p.phone(), MAX_PHONE);
        String email = limited("email", p.email(), MAX_EMAIL);
        if (email != null && !email.isEmpty() && !EMAIL.matcher(email).matches()) {
            throw invalid("email", "Adresse e-mail invalide");
        }
        String niu = limited("niu", p.niu(), MAX_NIU);
        String rccm = limited("rccm", p.rccm(), MAX_RCCM);
        String country = stripKeepingNull(p.country());
        if (country != null && !country.isEmpty()) {
            country = country.toUpperCase(java.util.Locale.ROOT);
            if (!country.matches("[A-Z]{2}")) {
                throw invalid("country", "Pays : code ISO à 2 lettres");
            }
        }
        return new DetailsPatch(designation, description, category, address, phone, email, niu, rccm, country);
    }

    private static String stripKeepingNull(String v) {
        return v == null ? null : v.strip();
    }

    private static String limited(String field, String v, int max) {
        String stripped = stripKeepingNull(v);
        if (stripped != null && stripped.length() > max) {
            throw invalid(field, "Maximum " + max + " caractères");
        }
        return stripped;
    }

    private static DomainException invalid(String field, String reason) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid company profile: " + field,
                Map.of("field", field, "reason", reason));
    }

    @Transactional
    public CompanyProfile uploadLogo(String tenantSchema, byte[] upload, UUID actorId) {
        TenantIdentity t = tenant(tenantSchema);
        if (upload == null || upload.length == 0) {
            throw new DomainException(ErrorCode.COMPANY_LOGO_INVALID, "Empty company logo");
        }
        if (upload.length > maxLogoBytes) {
            throw new DomainException(ErrorCode.COMPANY_LOGO_TOO_LARGE,
                    "Company logo of " + upload.length + " bytes over " + maxLogoBytes);
        }
        Logo logo = normalizer.normalize(upload);
        boolean had = logos.exists(tenantSchema);
        try {
            logos.store(TenantSchema.validate(tenantSchema), logo.content(), logo.contentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Company logo could not be stored", e);
        }
        publish(t, t, map("logo", null), map("logo", had ? "Remplacé" : "Ajouté"),
                actorId, tenantSchema);
        return profile(t, true);
    }

    @Transactional
    public CompanyProfile deleteLogo(String tenantSchema, UUID actorId) {
        TenantIdentity t = tenant(tenantSchema);
        boolean had = logos.exists(tenantSchema);
        logos.delete(tenantSchema);
        if (had) {
            publish(t, t, map("logo", null), map("logo", "Supprimé"), actorId, tenantSchema);
        }
        return profile(t, false);
    }

    @Transactional(readOnly = true)
    public Logo readLogo(String tenantSchema) {
        return logo(tenantSchema).orElseThrow(() -> new DomainException(ErrorCode.COMPANY_LOGO_NOT_FOUND,
                "No company logo"));
    }

    private TenantIdentity tenant(String tenantSchema) {
        return repository.find(TenantSchema.validate(tenantSchema))
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found"));
    }

    private void publish(TenantIdentity before, TenantIdentity after, Map<String, Object> valuesBefore,
                         Map<String, Object> valuesAfter, UUID actorId, String tenantSchema) {
        Instant now = clock.instant();
        events.publishEvent(new CompanyProfileUpdatedEvent(before.id(), displayName(after), valuesBefore,
                valuesAfter, actorId, tenantSchema, now));
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (value != null) {
            m.put(key, value);
        }
        return m;
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
