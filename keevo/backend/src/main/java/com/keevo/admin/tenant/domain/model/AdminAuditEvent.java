package com.keevo.admin.tenant.domain.model;

import java.time.Instant;

/**
 * AdminAuditEvent — one audit log entry in the tenant detail view. Carries no identifier: the
 * dashboard renders the action label, the entity label and the actor's name only.
 *
 * @param action      audit action code (labelled by the dashboard)
 * @param entityType  audited object type (labelled by the dashboard)
 * @param entityLabel human label of the object (v1s-18-6), null for older rows
 * @param actorName   employee name, « Propriétaire », « Système » (SYSTEM source) or « Utilisateur inconnu »
 * @param storeName   store of the event, null when none
 */
public record AdminAuditEvent(
        String action,
        String entityType,
        String entityLabel,
        String actorName,
        String storeName,
        Instant occurredAt
) {}
