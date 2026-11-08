package com.keevo.admin;

/**
 * AdminLabels — French fallback labels shared by the admin read models (spec 9-1 completion).
 * One constant per label so the tenant list/drawer, the feedback inbox and the dashboard agree.
 */
public final class AdminLabels {

    /** Owner without a stored name. */
    public static final String OWNER = "Propriétaire";
    /** Employee without a stored name. */
    public static final String EMPLOYEE = "Employé";
    /** Story 19.5 — manager without a stored name. */
    public static final String MANAGER = "Gérant";
    /** Story 19.5 — read-only member without a stored name. */
    public static final String VIEWER = "Lecteur";
    /** Actor of an automatic (SYSTEM) audit event. */
    public static final String SYSTEM = "Système";
    /** User that cannot be resolved (not a member, schema unreadable). */
    public static final String UNKNOWN_USER = "Utilisateur inconnu";
    /** Company whose name is blank. */
    public static final String UNNAMED_TENANT = "Entreprise sans nom";
    /** Company deleted (or unknown) — its feedback stays readable. */
    public static final String DELETED_TENANT = "Entreprise supprimée";

    /** Story 21.10 — payment whose plan is unknown (legacy row). */
    public static final String UNKNOWN_PLAN = "Plan non précisé";
    /** Story 21.10 — payment without a billing cycle (free-form period). */
    public static final String FREE_FORM_CYCLE = "Durée libre";

    private AdminLabels() {}
}
