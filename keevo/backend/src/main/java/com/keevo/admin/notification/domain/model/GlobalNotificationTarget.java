package com.keevo.admin.notification.domain.model;

import com.keevo.identity.api.PlanKind;

import java.util.Arrays;
import java.util.Optional;

/**
 * Audience of a global push (Story 9-5, FR82): every active company, companies of one kind of plan, or a single
 * company. Story 19.7a: {@code PREMIUM} / {@code TRIAL} / {@code FREE} target every plan of kind {@code PAID} /
 * {@code TRIAL} / {@code FREE} (plan catalogue), so a plan created as data is reached like the seeded one of its kind;
 * the stored target names are unchanged.
 */
public enum GlobalNotificationTarget {
    ALL(null),
    PREMIUM(PlanKind.PAID),
    TRIAL(PlanKind.TRIAL),
    FREE(PlanKind.FREE),
    TENANT(null);

    private final PlanKind planKind;

    GlobalNotificationTarget(PlanKind planKind) {
        this.planKind = planKind;
    }

    /** Kind of plan this target filters on, or null. */
    public PlanKind planKind() {
        return planKind;
    }

    /** The target reaching the plans of this kind. */
    public static Optional<GlobalNotificationTarget> forKind(PlanKind kind) {
        return Arrays.stream(values()).filter(t -> t.planKind != null && t.planKind == kind).findFirst();
    }
}
