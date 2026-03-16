package com.keevo.identity.employee.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * EmployeeJpaEntity — JPA entity for the employees table (tenant schema).
 *
 * <p>No schema qualifier — routes via TenantContext search_path.
 * Story 3.5 — AC1.
 */
@Entity
@Table(name = "employees")
public class EmployeeJpaEntity extends JpaBaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    public EmployeeJpaEntity() {}

    /** Sets the entity ID (delegates to JpaBaseEntity.setId). */
    public void assignId(java.util.UUID id) { setId(id); }

    // ── Getters / Setters ────────────────────────────────────────

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getStoreId() { return storeId; }
    public void setStoreId(UUID storeId) { this.storeId = storeId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public void setPasswordChangeRequired(boolean passwordChangeRequired) {
        this.passwordChangeRequired = passwordChangeRequired;
    }
}
