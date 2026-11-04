package com.keevo.shared.capability.entitlement;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;

import java.util.List;

/**
 * Test fixture (Story 19.7b): the V42 catalogue — FREE / PREMIUM_TRIAL / PREMIUM all live, 6-month PREMIUM_TRIAL
 * trial, default paid plan PREMIUM — for unit tests written against the Keevo offer, now that the built-in
 * {@link PlanCatalog#SEED} mirrors the merged offer (V43: PREMIUM_TRIAL and PREMIUM archived).
 */
public final class TestPlanCatalogs {

    public static final PlanCatalog V42 = PlanCatalog.of(List.of(
                    new PlanDefinition(PlanType.FREE, "Gratuit", null, 1, true, PlanKind.FREE, null),
                    new PlanDefinition(PlanType.PREMIUM_TRIAL, "Essai Premium", null, 2, true, PlanKind.TRIAL, null),
                    new PlanDefinition(PlanType.PREMIUM, "Premium", null, 3, true, PlanKind.PAID, null)),
            new PlanSettings(PlanType.FREE, PlanType.PREMIUM_TRIAL, 6, PlanType.PREMIUM));

    private TestPlanCatalogs() {
    }
}
