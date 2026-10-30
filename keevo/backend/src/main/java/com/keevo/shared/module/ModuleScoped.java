package com.keevo.shared.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates event listeners by module state, per tenant (Story 13.9, spec-socle §7.5): an event listener method
 * ({@code @EventListener}, {@code @TransactionalEventListener}, {@code @ApplicationModuleListener}) annotated — or
 * declared in a class annotated — with {@code @ModuleScoped("<module id>")} is <b>not invoked</b> when the module is
 * {@link ModuleState#DISABLED} for the event's tenant ({@code TenantScopedEvent.tenantSchema()}): the delivery is
 * considered done (the event publication is completed, a DEBUG line names the tenant code). A {@code READ_ONLY}
 * module still consumes (its listeners read past data). An event without tenant is always delivered. A module id
 * that is not installed fails the boot; so does the annotation on a method that is not an event listener.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ModuleScoped {

    /** The id of the module owning the listener (its manifest {@code id}). */
    String value();
}
