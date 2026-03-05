package com.keevo.identity.auth.adapter.out.persistence.entity;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * TenantJpaEntity — JPA mapping for the public.tenants table.
 *
 * <p>Infrastructure layer only — NEVER used in domain or application layer.
 * The table structure is derived automatically from this entity via ddl-auto=update.
 * Domain model: {@link com.keevo.identity.auth.domain.model.Tenant}.
 */
@Entity
@Table(name = "tenants")
public class TenantJpaEntity extends JpaBaseEntity {

    @Column(name = "code", unique = true, nullable = false, length = 10)
    private String code;

    @Column(name = "schema_name", unique = true, nullable = false, length = 15)
    private String schemaName;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "plan_type", nullable = false, length = 20)
    private String planType;

    @Column(name = "max_stores", nullable = false)
    private int maxStores;

    @Column(name = "max_products", nullable = false)
    private int maxProducts;

    @Column(name = "max_employees", nullable = false)
    private int maxEmployees;

    protected TenantJpaEntity() {}

    public TenantJpaEntity(UUID id, String code, String schemaName, String status,
                            String planType, int maxStores, int maxProducts, int maxEmployees) {
        if (id != null) setId(id);
        this.code         = code;
        this.schemaName   = schemaName;
        this.status       = status;
        this.planType     = planType;
        this.maxStores    = maxStores;
        this.maxProducts  = maxProducts;
        this.maxEmployees = maxEmployees;
    }

    public String getCode()        { return code; }
    public String getSchemaName()  { return schemaName; }
    public String getStatus()      { return status; }
    public String getPlanType()    { return planType; }
    public int    getMaxStores()   { return maxStores; }
    public int    getMaxProducts() { return maxProducts; }
    public int    getMaxEmployees(){ return maxEmployees; }
}
