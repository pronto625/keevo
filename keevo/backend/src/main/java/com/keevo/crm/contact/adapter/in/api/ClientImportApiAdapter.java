package com.keevo.crm.contact.adapter.in.api;

import com.keevo.crm.api.ClientImportPort;
import com.keevo.crm.contact.application.service.ClientProfileRules;
import com.keevo.crm.contact.domain.entity.ClientType;
import com.keevo.crm.contact.domain.port.out.ClientImportWriter;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.3 — implements {@link ClientImportPort}: every client is checked against the rules a stored client must
 * meet to be read back ({@code Client}: non-blank name, E.164 phone; {@link ClientProfileRules} limits; known type)
 * before one batch insert. Joins the caller's transaction; the tenant context must be the explicit schema.
 */
@Component
class ClientImportApiAdapter implements ClientImportPort {

    private static final int MAX_NAME = 200;
    private static final int MAX_EMAIL = 255;

    private final ClientImportWriter writer;

    ClientImportApiAdapter(ClientImportWriter writer) {
        this.writer = writer;
    }

    @Override
    public Set<UUID> importClients(String tenantSchema, List<ImportedClient> clients) {
        TenantContext.requireCurrent(tenantSchema);
        if (clients == null || clients.isEmpty()) {
            return Set.of();
        }
        clients.forEach(ClientImportApiAdapter::check);
        return writer.insertAbsent(tenantSchema, clients);
    }

    static void check(ImportedClient c) {
        require(!c.name().isBlank() && fits(c.name(), MAX_NAME), "client name");
        require(c.phone().equals(PhoneNumbers.toE164(c.phone(), PhoneNumbers.CAMEROON)), "client phone not E.164");
        require(c.email() == null || (fits(c.email(), MAX_EMAIL) && c.email().contains("@")), "client email");
        require(c.clientType() == null || ClientType.parse(c.clientType()).map(Enum::name)
                .filter(c.clientType()::equals).isPresent(), "client type");
        require(fits(c.niu(), ClientProfileRules.MAX_NIU), "client NIU");
        require(fits(c.rccm(), ClientProfileRules.MAX_RCCM), "client RCCM");
        require(fits(c.address(), ClientProfileRules.MAX_ADDRESS), "client address");
        require(fits(c.contactName(), ClientProfileRules.MAX_CONTACT_NAME), "client contact name");
        require(fits(c.website(), ClientProfileRules.MAX_WEBSITE), "client website");
    }

    private static boolean fits(String value, int max) {
        return value == null || value.codePointCount(0, value.length()) <= max;
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("invalid " + what);
        }
    }
}
