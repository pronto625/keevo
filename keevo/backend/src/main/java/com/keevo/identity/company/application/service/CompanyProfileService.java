package com.keevo.identity.company.application.service;

import com.keevo.identity.company.domain.port.out.CompanyLogoNormalizer;
import com.keevo.identity.company.domain.port.out.CompanyLogoStorage;
import com.keevo.identity.company.domain.port.out.CompanyProfileRepository;
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
