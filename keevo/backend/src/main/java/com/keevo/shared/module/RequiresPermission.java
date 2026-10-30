package com.keevo.shared.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative permission gate (Story 14.1, spec-socle §9.2): a method — or every method of a type — runs only if the
 * authenticated user holds <b>every</b> listed permission. Permissions are declared by module manifests
 * ({@code module.json} {@code permissions}) and mapped to roles by the modules: {@code OWNER} holds every declared
 * permission, {@code EMPLOYEE} those of {@link KeevoModule#employeePermissions()}, {@code SUPER_ADMIN} none.
 *
 * <p>Enforced through Spring method security ({@code shared.module.infrastructure.PermissionMethodSecurityConfig}):
 * after {@code @PreAuthorize} and before the entitlement aspect ({@link RequiresEntitlement}, order 1000), so a role
 * that lacks the permission gets 403 {@code FORBIDDEN} even when the module is disabled. A method-level annotation
 * overrides the type-level one. Every value must be declared by an installed manifest (checked at boot).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    /** The required permissions (all of them), e.g. {@code "INVOICE_CREATE"}. */
    String[] value();
}
