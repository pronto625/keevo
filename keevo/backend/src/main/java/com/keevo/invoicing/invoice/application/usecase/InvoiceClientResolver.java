package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.crm.api.ClientLookupPort;
import com.keevo.crm.api.ClientSummary;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The optional CRM integration of invoicing (Story 14.3, MOD-FR9). The {@code crm} capability
 * {@link ClientLookupPort} is resolved <b>per call</b> through {@link CapabilityRegistry#find} (never injected): it is
 * absent while {@code crm} is off for the tenant, and invoicing then degrades to the free-text client of 14.1 with
 * no error. Exceptions of the provider propagate (never swallowed).
 *
 * <ul>
 *   <li>{@link #resolve}: no {@code clientId} → free text; {@code clientId} with crm on → the client must exist
 *       ({@code CLIENT_NOT_FOUND} otherwise), the link is kept and a blank name / phone is filled from the CRM
 *       (typed values win); {@code clientId} with crm off → the id is dropped, the typed text is kept. A blank name
 *       after resolution → {@code VALIDATION_ERROR} « Le nom du client est obligatoire ».</li>
 *   <li>{@link #suggest}: the CRM clients matching a search, for the invoice client picker.</li>
 * </ul>
 */
@Component
public class InvoiceClientResolver {

    /** Default number of suggestions. */
    public static final int DEFAULT_LIMIT = 20;

    private final CapabilityRegistry capabilities;

    public InvoiceClientResolver(CapabilityRegistry capabilities) {
        this.capabilities = capabilities;
    }

    /** A CRM client offered by the picker. */
    public record ClientSuggestion(UUID id, String name, String phone) {
    }

    /** The picker's answer: whether the CRM is available for the tenant, and the matching clients. */
    public record ClientSuggestions(boolean crmAvailable, List<ClientSuggestion> clients) {
    }

    /** The invoice client of a new draft after the CRM integration (see the class comment). */
    public ClientInfo resolve(ClientInfo typed, String tenantSchema) {
        return resolve(typed, tenantSchema, null);
    }

    /**
     * The invoice client after the CRM integration (see the class comment). {@code storedClientId} = the CRM link the
     * draft already has: with crm off it is kept when the request repeats it (module data is kept while the module is
     * off); only a new or different id is dropped.
     */
    public ClientInfo resolve(ClientInfo typed, String tenantSchema, UUID storedClientId) {
        ClientInfo resolved = typed;
        if (typed != null && typed.clientId() != null) {
            Optional<ClientLookupPort> crm = capabilities.find(ClientLookupPort.class, tenantSchema);
            if (crm.isEmpty()) {
                // Degraded mode: the CRM cannot vouch for a new id — keep the typed text, and the stored link only.
                UUID kept = typed.clientId().equals(storedClientId) ? storedClientId : null;
                resolved = new ClientInfo(kept, typed.name(), typed.niu(), typed.address(), typed.phone());
            } else {
                ClientSummary known = crm.get().find(tenantSchema, typed.clientId())
                        .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND, "Unknown CRM client"));
                resolved = new ClientInfo(known.id(),
                        typed.name() != null ? typed.name() : known.name(),
                        typed.niu(), typed.address(),
                        typed.phone() != null ? typed.phone() : known.phone());
            }
        }
        if (resolved == null || resolved.name() == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "A client name is required",
                    Map.of("reason", "Le nom du client est obligatoire"));
        }
        return resolved;
    }

    /** The CRM clients matching {@code search} (non-archived, sorted by name); {@code crmAvailable=false} when off. */
    public ClientSuggestions suggest(String search, int limit, String tenantSchema) {
        Optional<ClientLookupPort> crm = capabilities.find(ClientLookupPort.class, tenantSchema);
        if (crm.isEmpty()) {
            return new ClientSuggestions(false, List.of());
        }
        List<ClientSuggestion> clients = crm.get().search(tenantSchema, search, limit).stream()
                .map(c -> new ClientSuggestion(c.id(), c.name(), c.phone()))
                .toList();
        return new ClientSuggestions(true, clients);
    }
}
