package com.keevo.feedback.feedback.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Feedback — Domain model for user-submitted feedback (Story 14.5, FR92).
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
 *
 * <p>v1s-15-9: the V7 NOT NULL columns (id, type, description, tenantId, userId, submittedAt,
 * priority) are required; appVersion, platform and screenContext stay nullable.
 */
public final class Feedback {

    private final UUID id;
    private final String type;
    private final String description;
    private final String tenantId;       // schema name (e.g., "kv_abc123")
    private final UUID userId;
    private final String appVersion;
    private final String platform;
    private final String screenContext;
    private final Instant submittedAt;
    private final String priority;

    public Feedback(UUID id, String type, String description,
                    String tenantId, UUID userId,
                    String appVersion, String platform, String screenContext,
                    Instant submittedAt, String priority) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.description = Objects.requireNonNull(description, "description");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.appVersion = appVersion;
        this.platform = platform;
        this.screenContext = screenContext;
        this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        this.priority = Objects.requireNonNull(priority, "priority");
    }

    public UUID getId()              { return id; }
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
