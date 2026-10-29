package com.keevo.crm.api;

import java.util.UUID;

/**
 * Write access to the crm's clients for other modules. Validation (name, E.164 phone, email),
 * audit events and transactions are those of the crm.
 */
public interface ClientCommandPort {

    /**
     * Persists a client with a caller-assigned id, without the audit event of the REST create
     * (offline-first sync create).
     *
     * @throws com.keevo.shared.domain.exception.DomainException VALIDATION_ERROR on invalid fields
     */
    ClientView createWithId(CreateClientInput input);

    ClientView update(UpdateClientInput input);

    void archive(UUID clientId, UUID actorId, String tenantId);

    void unarchive(UUID clientId, UUID actorId, String tenantId);
}
