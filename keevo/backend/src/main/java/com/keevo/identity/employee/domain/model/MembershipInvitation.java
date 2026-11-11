package com.keevo.identity.employee.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 19.6b — an invitation of an existing account to join a company: what the owner typed (role, store, names, the
 * identifier and its kind) and its state. Only the person's acceptance creates the membership and the employee row.
 *
 * <p>Pure Java — no framework import.
 *
 * @param identifierKind the kind of identifier the owner typed — the only identifier of the account the company sees
 * @param identifier     the identifier as typed (normalised phone or username)
 * @param invitedBy      the owner, manager or support account who invited (informational, no foreign key)
 */
public record MembershipInvitation(UUID id, UUID tenantId, UUID userId, String role, UUID storeId, String firstName,
                                   String lastName, IdentifierKind identifierKind, String identifier, UUID invitedBy,
                                   Status status, Instant invitedAt, Instant expiresAt) {

    /** The kind of identifier the owner typed. */
    public enum IdentifierKind { PHONE, USERNAME }

    /** PENDING → ACCEPTED | DECLINED | CANCELLED; a PENDING row past {@code expiresAt} is expired. */
    public enum Status { PENDING, ACCEPTED, DECLINED, CANCELLED }

    public MembershipInvitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(identifierKind, "identifierKind");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(status, "status");
    }

    /** A new PENDING invitation valid until {@code expiresAt}. */
    public static MembershipInvitation pending(UUID tenantId, UUID userId, String role, UUID storeId, String firstName,
                                               String lastName, IdentifierKind kind, String identifier,
                                               UUID invitedBy, Instant now, Instant expiresAt) {
        return new MembershipInvitation(UUID.randomUUID(), tenantId, userId, role, storeId, firstName, lastName, kind,
                identifier, invitedBy, Status.PENDING, now, expiresAt);
    }

    private static final Map<String, String> ROLE_LABELS = Map.of(
            "OWNER", "Propriétaire", "MANAGER", "Gérant", "EMPLOYEE", "Employé", "VIEWER", "Lecteur");

    /** French label of a membership role (« Employé », « Gérant », …), « Membre » when unknown. */
    public static String roleLabel(String role) {
        return role == null ? "Membre" : ROLE_LABELS.getOrDefault(role, "Membre");
    }

    /** « Prénom Nom » as typed by the owner. */
    public String displayName() {
        return ((firstName == null ? "" : firstName.trim()) + " " + (lastName == null ? "" : lastName.trim())).trim();
    }
}
