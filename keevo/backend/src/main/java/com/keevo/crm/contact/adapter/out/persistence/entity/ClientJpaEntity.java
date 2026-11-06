package com.keevo.crm.contact.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ClientJpaEntity — JPA mapping for {@code clients} table (Story 2.5).
 *
 * <p>Follows the same pattern as the catalog {@code ProductJpaEntity}.
 * No {@code @ManyToOne} — UUID FK only for simplicity.
 */
@Entity
@Table(name = "clients")
public class ClientJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "archived", nullable = false)
    private Boolean archived = false;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    // ── Story 20.2 — crm V2 (mirrored on public by V53) ──────────────────────
    @Column(name = "client_type", length = 10)
    private String clientType;

    @Column(name = "niu", length = 50)
    private String niu;

    @Column(name = "rccm", length = 50)
    private String rccm;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "contact_name", length = 200)
    private String contactName;

    @Column(name = "website", length = 255)
    private String website;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "store_id", columnDefinition = "UUID")
    private UUID storeId;

    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    /** Written only by the consent command (targeted UPDATE), never by a generic save: no lost update. */
    @Column(name = "marketing_consent_at", columnDefinition = "TIMESTAMPTZ", updatable = false)
    private Instant marketingConsentAt;

    /** The client's own STOP (21.5): never written by the crm, so not insertable/updatable from JPA. */
    @Column(name = "marketing_opt_out_at", columnDefinition = "TIMESTAMPTZ", insertable = false, updatable = false)
    private Instant marketingOptOutAt;

    @Column(name = "marketing_consent_by", columnDefinition = "UUID", updatable = false)
    private UUID marketingConsentBy;

    public ClientJpaEntity() {}

    public ClientJpaEntity(UUID id, String name, String phone, String email,
                           String notes, Boolean archived,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.notes = notes;
        this.archived = archived != null ? archived : false;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId()                { return id; }
    public void setId(UUID id)         { this.id = id; }

    public String getName()            { return name; }
    public void setName(String name)   { this.name = name; }

    public String getPhone()           { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail()           { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getNotes()           { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Boolean getArchived()              { return archived; }
    public void setArchived(Boolean archived) { this.archived = archived; }

    public Instant getCreatedAt()                   { return createdAt; }
    public void setCreatedAt(Instant createdAt)     { this.createdAt = createdAt; }

    public Instant getUpdatedAt()                   { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt)     { this.updatedAt = updatedAt; }

    public String getClientType()                  { return clientType; }
    public void setClientType(String clientType)   { this.clientType = clientType; }

    public String getNiu()                         { return niu; }
    public void setNiu(String niu)                 { this.niu = niu; }

    public String getRccm()                        { return rccm; }
    public void setRccm(String rccm)               { this.rccm = rccm; }

    public String getAddress()                     { return address; }
    public void setAddress(String address)         { this.address = address; }

    public String getContactName()                 { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getWebsite()                     { return website; }
    public void setWebsite(String website)         { this.website = website; }

    public LocalDate getBirthDate()                { return birthDate; }
    public void setBirthDate(LocalDate birthDate)  { this.birthDate = birthDate; }

    public UUID getStoreId()                       { return storeId; }
    public void setStoreId(UUID storeId)           { this.storeId = storeId; }

    public String getPhoneNormalized()                     { return phoneNormalized; }
    public void setPhoneNormalized(String phoneNormalized) { this.phoneNormalized = phoneNormalized; }

    public Instant getMarketingConsentAt()                        { return marketingConsentAt; }
    public void setMarketingConsentAt(Instant marketingConsentAt) { this.marketingConsentAt = marketingConsentAt; }

    public Instant getMarketingOptOutAt()                       { return marketingOptOutAt; }
    public void setMarketingOptOutAt(Instant marketingOptOutAt) { this.marketingOptOutAt = marketingOptOutAt; }

    public UUID getMarketingConsentBy()                     { return marketingConsentBy; }
    public void setMarketingConsentBy(UUID marketingConsentBy) { this.marketingConsentBy = marketingConsentBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClientJpaEntity that = (ClientJpaEntity) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return id != null ? id.hashCode() : 0; }
}
