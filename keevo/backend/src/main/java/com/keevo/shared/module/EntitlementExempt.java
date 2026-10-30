package com.keevo.shared.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit exemption of a REST handler — or of every handler of a controller — from the module entitlement gate
 * (Story 13.10a). Every {@code @RestController} handler carries either {@link RequiresEntitlement} or this
 * annotation (architecture test {@code EndpointEntitlementCoverageTest}); a method-level annotation overrides the
 * type-level one. Exempt: authentication, onboarding / registration, health, super-admin {@code /api/v1/admin/**},
 * sync push / pull (gated per operation), the owner's module list, and the {@code core} screens (account,
 * employees, feedback, tutorials, announcements, audit, subscription, tenant preferences).
 *
 * <p>Has no runtime behaviour: it only documents why the handler is not gated, and the reason appears in the
 * generated endpoint table ({@code docs/entitlements/endpoints.md}).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EntitlementExempt {

    /** Why the handler is not gated (short, shown in the endpoint table). */
    String value();
}
