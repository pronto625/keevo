package com.keevo.shared.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a bean implementing {@link Extension} as the contribution of a module to an extension point (Story
 * 13.7, spec-socle §8).
 *
 * <p>Put it on the bean class (a {@code @Component} of the contributing module) or on its {@code @Bean} method; the
 * bean must be singleton-scoped. It is not an autowire candidate: {@link ExtensionRegistry#contributions} is the
 * only way in. The {@link ExtensionRegistry} collects these beans once the context has started and fails the boot
 * (message naming the class, module and point) when: {@link #module()} is not installed; the bean does not
 * implement {@code Extension}; {@link #point()} is not in the module manifest's {@code extensionPoints.fills};
 * two contributions of a point share an {@link Extension#id()}; the id, label or action is blank; a non-null
 * {@link Extension#permission()} is not declared in the module manifest's {@code permissions} (Story 14.5); or a manifest
 * {@code fills} entry has no contribution bean.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ContributesExtension {

    /** Id of the contributing module (its manifest {@code id}); listed only while it is ENABLED for the tenant. */
    String module();

    /** Id of the extension point filled (declared by another manifest's {@code extensionPoints.declares}). */
    String point();
}
