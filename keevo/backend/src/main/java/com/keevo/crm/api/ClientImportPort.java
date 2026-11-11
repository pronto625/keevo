package com.keevo.crm.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Story 24.3 — client writes of the Bref migration tool: Bref customers imported with their id, their store (ADR-08:
 * one client per store, no merge across stores), their Bref fields, consent and dates, without event, idempotently.
 * The tenant context must be bound to {@code tenantSchema} (the caller opens a transaction in that schema); the method
 * joins the caller's transaction.
 */
public interface ClientImportPort {

    /**
     * Inserts the clients whose id is absent, in one statement ({@code ON CONFLICT (id) DO NOTHING}). The values must
     * already respect the client rules (E.164 phone, field limits): an invalid one throws
     * {@code IllegalArgumentException} before any insert.
     *
     * @return the ids inserted by this call
     */
    Set<UUID> importClients(String tenantSchema, List<ImportedClient> clients);

    /**
     * A client to import.
     *
     * @param phone      E.164
     * @param clientType {@code PERSON} | {@code COMPANY} | {@code null}
     */
    record ImportedClient(UUID id, UUID storeId, String name, String phone, String email, String notes,
                          String clientType, String niu, String rccm, String address, String contactName,
                          String website, LocalDate birthDate, Instant marketingConsentAt, UUID marketingConsentBy,
                          Instant marketingOptOutAt, Instant createdAt, Instant updatedAt) {

        public ImportedClient {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(storeId, "storeId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(phone, "phone");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        @Override
        public String toString() {
            return "ImportedClient[" + id + "]"; // no personal data in logs
        }
    }
}
