package com.keevo.crm.api;

import java.util.UUID;

/**
 * Write access to the crm's clients for other modules. Validation (name, E.164 phone, email),
 * audit events and transactions are those of the crm.
 */
public interface ClientCommandPort {

    ClientView update(String tenantSchema, UpdateClientInput input);

    void archive(String tenantSchema, UUID clientId, UUID actorId);

    void unarchive(String tenantSchema, UUID clientId, UUID actorId);
}
