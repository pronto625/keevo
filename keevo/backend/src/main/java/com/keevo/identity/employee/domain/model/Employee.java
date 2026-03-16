package com.keevo.identity.employee.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Employee — Domain model for a tenant-scoped employee record.
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
 * JPA mapping lives in {@code adapter/out/persistence/EmployeeJpaEntity}.
 *
 * <p>Story 3.5 — AC1, AC6, AC7, AC8.
 */
public final class Employee {

    private final UUID id;
    private final UUID userId;
    private final UUID storeId;
    private final String firstName;
    private final String lastName;
    private final EmployeeStatus status;
    private final boolean passwordChangeRequired;
    private final Instant createdAt;

    public Employee(UUID id, UUID userId, UUID storeId, String firstName, String lastName,
                    EmployeeStatus status, boolean passwordChangeRequired, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.storeId = Objects.requireNonNull(storeId, "storeId must not be null");
        this.firstName = Objects.requireNonNull(firstName, "firstName must not be null");
        this.lastName = Objects.requireNonNull(lastName, "lastName must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.passwordChangeRequired = passwordChangeRequired;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Factory — creates a new ACTIVE employee with passwordChangeRequired = true.
     */
    public static Employee create(UUID userId, UUID storeId, String firstName, String lastName) {
        return new Employee(UUID.randomUUID(), userId, storeId, firstName, lastName,
                EmployeeStatus.ACTIVE, true, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getStoreId() { return storeId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public EmployeeStatus getStatus() { return status; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public Instant getCreatedAt() { return createdAt; }

    public Employee withStatus(EmployeeStatus newStatus) {
        return new Employee(id, userId, storeId, firstName, lastName, newStatus, passwordChangeRequired, createdAt);
    }

    public Employee withStoreId(UUID newStoreId) {
        return new Employee(id, userId, newStoreId, firstName, lastName, status, passwordChangeRequired, createdAt);
    }

    public Employee withPasswordChangeRequired(boolean required) {
        return new Employee(id, userId, storeId, firstName, lastName, status, required, createdAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Employee e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "Employee{id=" + id + ", userId=" + userId + ", storeId=" + storeId
               + ", name=" + firstName + " " + lastName + ", status=" + status + "}";
    }
}
