package com.keevo.feedback.feedback.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.JpaBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * FeedbackJpaEntity — JPA mapping for the public.feedback table (Story 14.5, FR92).
 */
@Entity
@Table(name = "feedback", schema = "public")
public class FeedbackJpaEntity extends JpaBaseEntity {

    @Column(name = "type", nullable = false, length = 30)
    private String type;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "tenant_id", nullable = false, length = 15)
    private String tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "app_version", length = 20)
    private String appVersion;

    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "screen_context", length = 200)
    private String screenContext;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "priority", nullable = false, length = 10)
    private String priority;

    protected FeedbackJpaEntity() {}

    public FeedbackJpaEntity(UUID id, String type, String description,
                             String tenantId, UUID userId,
                             String appVersion, String platform, String screenContext,
                             Instant submittedAt, String priority) {
        if (id != null) setId(id);
        this.type = type;
        this.description = description;
        this.tenantId = tenantId;
        this.userId = userId;
        this.appVersion = appVersion;
        this.platform = platform;
        this.screenContext = screenContext;
        this.submittedAt = submittedAt;
        this.priority = priority;
    }

    public String getType()          { return type; }
    public String getDescription()   { return description; }
    public String getTenantId()        { return tenantId; }
    public UUID getUserId()          { return userId; }
    public String getAppVersion()    { return appVersion; }
    public String getPlatform()      { return platform; }
    public String getScreenContext() { return screenContext; }
    public Instant getSubmittedAt()  { return submittedAt; }
    public String getPriority()      { return priority; }
}
