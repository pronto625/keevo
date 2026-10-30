package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.RequiresPermission;
import com.keevo.shared.module.application.ModulePermissions;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Story 14.1 — decides {@link RequiresPermission} (spec-socle §9.2): granted when the authenticated user's roles
 * ({@code ROLE_<role>} authorities set by the JWT filter) hold every listed permission ({@link ModulePermissions}).
 * No authentication, an anonymous one, or an unresolvable annotation → denied (never an open door).
 */
public class PermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private final ObjectProvider<ModulePermissions> permissions;

    public PermissionAuthorizationManager(ObjectProvider<ModulePermissions> permissions) {
        this.permissions = permissions;
    }

    @Override
    public AuthorizationResult authorize(Supplier<Authentication> authentication, MethodInvocation invocation) {
        RequiresPermission required = resolve(invocation);
        if (required == null || required.value().length == 0) {
            return new AuthorizationDecision(false);
        }
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }
        Set<String> held = permissions.getObject().permissionsOf(ModulePermissions.rolesOf(auth));
        return new AuthorizationDecision(held.containsAll(Arrays.asList(required.value())));
    }

    /** @deprecated Spring Security 6.4+ calls {@link #authorize}; kept for the interface contract. */
    @Override
    @Deprecated
    @SuppressWarnings("deprecation")
    public AuthorizationDecision check(Supplier<Authentication> authentication, MethodInvocation invocation) {
        return (AuthorizationDecision) authorize(authentication, invocation);
    }

    /** The annotation of the executed method (most specific), else of its class, else of the target class. */
    static RequiresPermission resolve(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Class<?> targetClass = target != null ? AopUtils.getTargetClass(target) : method.getDeclaringClass();
        Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
        RequiresPermission found = AnnotationUtils.findAnnotation(specific, RequiresPermission.class);
        if (found == null) {
            found = AnnotationUtils.findAnnotation(specific.getDeclaringClass(), RequiresPermission.class);
        }
        if (found == null) {
            found = AnnotationUtils.findAnnotation(targetClass, RequiresPermission.class);
        }
        return found;
    }
}
