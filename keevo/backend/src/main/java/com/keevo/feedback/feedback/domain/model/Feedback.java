package com.keevo.feedback.feedback.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Feedback — Domain model for user-submitted feedback (Story 14.5, FR92).
 *
 * <p>Pure Java — NO Spring, JPA, or framework imports.
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
        this.id = id;
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
