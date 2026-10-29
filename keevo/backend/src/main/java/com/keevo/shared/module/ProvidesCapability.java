package com.keevo.shared.module;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a bean as the implementation of one or more {@link Capability capabilities} of a module (Story 13.6).
 *
 * <p>Put it on the bean class (a {@code @Component} of the provider module) or on its {@code @Bean} method; the
 * bean must be singleton-scoped. It is not an autowire candidate (nobody can inject it and skip the tenant check:
 * {@link CapabilityRegistry#find} is the only way in). The capability types are
 * {@link #value()} when given, otherwise every interface extending {@link Capability} the class implements. The
 * {@link CapabilityRegistry} collects these beans once the context has started and fails the boot (message naming
 * the class and the module) when: {@link #module()} is not installed; a type is not an interface extending
 * {@code Capability} (or is not implemented by the bean); the bean has no capability type; two beans provide the
 * same type; a type's simple name is missing from the module manifest's {@code provides}; or a manifest
 * {@code provides} entry has no provider bean.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ProvidesCapability {

    /** Id of the provider module (its manifest {@code id}); the capability is resolvable only while it is usable. */
    String module();

    /**
     * The capability types provided; empty = the most specific {@link Capability} sub-interfaces the bean implements
     * (an interface that another implemented capability extends is not registered on its own).
     */
    Class<? extends Capability>[] value() default {};
}
