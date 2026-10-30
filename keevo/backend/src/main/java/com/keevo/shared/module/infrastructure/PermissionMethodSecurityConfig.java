package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.RequiresPermission;
import com.keevo.shared.module.application.ModulePermissions;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;

/**
 * Story 14.1 — registers the {@link RequiresPermission} interceptor in Spring method security
 * ({@code @EnableMethodSecurity} picks up {@link Advisor} beans): order {@link #ORDER} = right after
 * {@code @PreAuthorize}, before the entitlement aspect ({@code EntitlementAspect}, order 1000) and
 * {@code @Transactional}. So the evaluation order of a gated handler is: role ({@code @PreAuthorize}) → permission →
 * entitlement → transaction; a denied permission throws {@code AuthorizationDeniedException} → 403
 * {@code FORBIDDEN} (« Vous n'avez pas les droits nécessaires »).
 */
@Configuration(proxyBeanMethods = false)
public class PermissionMethodSecurityConfig {

    /** Right after {@code @PreAuthorize}; strictly lower than {@code EntitlementAspect.ORDER}. */
    public static final int ORDER = AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1;

    /** Methods annotated directly, or declared by an annotated class (method annotation wins at decision time). */
    static Pointcut pointcut() {
        return new ComposablePointcut(new AnnotationMatchingPointcut(null, RequiresPermission.class, true))
                .union(new AnnotationMatchingPointcut(RequiresPermission.class, true));
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor requiresPermissionAuthorizationAdvisor(ObjectProvider<ModulePermissions> permissions) {
        AuthorizationManagerBeforeMethodInterceptor interceptor = new AuthorizationManagerBeforeMethodInterceptor(
                pointcut(), new PermissionAuthorizationManager(permissions));
        interceptor.setOrder(ORDER);
        return interceptor;
    }
}
