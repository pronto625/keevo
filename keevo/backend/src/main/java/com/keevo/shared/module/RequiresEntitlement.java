package com.keevo.shared.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative entitlement gate (Story 13.5, spec-socle §9.2): a method — or every method of a type — runs only if
 * the current tenant ({@code TenantContext}) is entitled to the module. Enforced by
 * {@code shared.module.infrastructure.EntitlementAspect} through {@link EntitlementService}:
 * {@link Access#READ} → {@link EntitlementService#assertEnabled}, {@link Access#WRITE} (default) →
 * {@link EntitlementService#assertWritable}. A method-level annotation overrides the type-level one.
 *
 * <p>No tenant bound when the gated method runs → {@link IllegalStateException} (never an open door). The module
 * id must name an installed module: an unknown id fails the application startup, naming the class.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresEntitlement {

    /** The module id ({@code module.json} {@code id}). */
    String value();

    /** What the method does with the module: {@link Access#WRITE} by default. */
    Access access() default Access.WRITE;

    /** Kind of access a gated method needs. */
    enum Access {
        /** Reads only: allowed while the module is read-only (expired, in grace). */
        READ,
        /** Writes: refused while the module is read-only. */
        WRITE
    }
}
