package com.keevo.shared.module.infrastructure;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.RequiresEntitlement;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Enforces {@link RequiresEntitlement} (Story 13.5, spec-socle §9.2) on Spring beans (use cases, controllers): the
 * tenant comes from {@link TenantContext}; {@code READ} → {@link EntitlementService#assertEnabled}, {@code WRITE} →
 * {@link EntitlementService#assertWritable}. No tenant bound → {@link IllegalStateException}, never a pass.
 * Story 14.1: once the check passed, the module's tables are ensured in the tenant schema
 * ({@link ModuleProvisioner#ensureProvisioned}, cached — a warm call is a map lookup; failure → 503).
 *
 * <p>Order {@value #ORDER}: after Spring Security's method authorization interceptors (role check first, then
 * {@code @RequiresPermission}, Story 14.1) and
 * before {@code @Transactional} (lowest precedence), so a refused call never opens a transaction.
 *
 * <p>Put the annotation on the bean's class or on its (public, proxied) methods: like every Spring AOP advice, a
 * self-invocation inside the bean is not intercepted, and an annotation placed only on an interface method is not
 * matched.
 */
@Aspect
@Component
@Order(EntitlementAspect.ORDER)
public class EntitlementAspect {

    static final int ORDER = 1_000;

    private final EntitlementService entitlements;
    private final ObjectProvider<ModuleProvisioner> provisioner;

    /** Without lazy provisioning (unit tests). */
    public EntitlementAspect(EntitlementService entitlements) {
        this(entitlements, null);
    }

    /**
     * @param provisioner Story 14.1 — once the check passed, the module's tables are ensured in the tenant schema
     *                    ({@link ModuleProvisioner#ensureProvisioned}); absent (slice tests) → skipped
     */
    @Autowired
    public EntitlementAspect(EntitlementService entitlements, ObjectProvider<ModuleProvisioner> provisioner) {
        this.entitlements = entitlements;
        this.provisioner = provisioner;
    }

    /**
     * Gated join points: a method annotated directly, or any method declared by an annotated class — minus
     * {@code Object} methods ({@code toString}/{@code equals}/{@code hashCode}, also when overridden) and Spring MVC
     * handler infrastructure ({@code @ExceptionHandler}, {@code @InitBinder}, {@code @ModelAttribute}), which are not
     * requests of the module. Placements this pointcut cannot advise are refused at startup by
     * {@link RequiresEntitlementValidator}.
     */
    static final String POINTCUT = "(@annotation(com.keevo.shared.module.RequiresEntitlement) "
            + "|| @within(com.keevo.shared.module.RequiresEntitlement)) "
            + "&& !execution(* java.lang.Object.*(..)) "
            + "&& !@annotation(org.springframework.web.bind.annotation.ExceptionHandler) "
            + "&& !@annotation(org.springframework.web.bind.annotation.InitBinder) "
            + "&& !@annotation(org.springframework.web.bind.annotation.ModelAttribute)";

    @Around(POINTCUT)
    public Object gate(ProceedingJoinPoint joinPoint) throws Throwable {
        RequiresEntitlement gate = resolve(joinPoint);
        String tenant = TenantContext.getCurrentTenant();
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("@RequiresEntitlement method called without a tenant bound");
        }
        if (gate.access() == RequiresEntitlement.Access.READ) {
            entitlements.assertEnabled(tenant, gate.value());
        } else {
            entitlements.assertWritable(tenant, gate.value());
        }
        ModuleProvisioner modules = provisioner != null ? provisioner.getIfAvailable() : null;
        if (modules != null) {
            modules.ensureProvisioned(tenant, gate.value());
        }
        return joinPoint.proceed();
    }

    /**
     * The annotation declared on the executed method, else on its declaring class, else on the target class — the
     * only placements the validator accepts. None found although the pointcut matched → fail closed.
     */
    private static RequiresEntitlement resolve(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> targetClass = joinPoint.getTarget() != null
                ? AopUtils.getTargetClass(joinPoint.getTarget()) : method.getDeclaringClass();
        Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
        RequiresEntitlement found = specific.getAnnotation(RequiresEntitlement.class);
        if (found == null) {
            found = specific.getDeclaringClass().getDeclaredAnnotation(RequiresEntitlement.class);
        }
        if (found == null) {
            found = targetClass.getDeclaredAnnotation(RequiresEntitlement.class);
        }
        if (found == null) {
            throw new IllegalStateException("@RequiresEntitlement advice without a resolvable annotation");
        }
        return found;
    }
}
