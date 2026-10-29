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
    ClientView createWithId(String tenantSchema, CreateClientInput input);

    ClientView update(String tenantSchema, UpdateClientInput input);

    void archive(String tenantSchema, UUID clientId, UUID actorId);

    void unarchive(String tenantSchema, UUID clientId, UUID actorId);
}
