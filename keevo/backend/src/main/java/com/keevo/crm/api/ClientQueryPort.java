package com.keevo.crm.api;

import java.util.UUID;

/** Read access to the crm's clients for other modules. */
public interface ClientQueryPort {

    boolean existsById(String tenantSchema, UUID clientId);
}
