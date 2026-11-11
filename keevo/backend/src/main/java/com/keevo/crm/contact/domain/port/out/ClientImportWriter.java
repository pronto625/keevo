package com.keevo.crm.contact.domain.port.out;

import com.keevo.crm.api.ClientImportPort.ImportedClient;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Story 24.3 — set-based persistence of the Bref clients import, on an explicit, validated tenant schema. */
public interface ClientImportWriter {

    /** Inserts the clients whose id is absent, one statement; returns the ids inserted. */
    Set<UUID> insertAbsent(String tenantSchema, List<ImportedClient> clients);
}
