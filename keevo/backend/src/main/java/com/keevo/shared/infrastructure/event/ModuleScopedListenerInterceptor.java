package com.keevo.shared.infrastructure.event;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleScoped;
import com.keevo.shared.module.ModuleState;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-tenant gate of {@link ModuleScoped} event listeners (Story 13.9, spec-socle §7.5). Wraps asynchronous module
 * listeners ({@code @ApplicationModuleListener}) and synchronous ones ({@code @TransactionalEventListener},
 * {@code @EventListener}) alike: when the listener's module is {@link ModuleState#DISABLED} for the event's tenant
 * ({@code TenantScopedEvent.tenantSchema()}), the listener is not invoked and the call returns normally — so the
 * Event Publication Registry marks the publication completed (Modulith's completion interceptor runs outside this
 * one) and a DEBUG line names the event, module and tenant code. READ_ONLY and ENABLED modules consume. An event
 * without (valid) tenant is always delivered; a failure reading the module state is logged at WARN and the event
 * delivered (no behaviour change when the registry is unavailable). A skipped event is not replayed when the module
 * is reactivated: e.g. no end-of-day report is generated afterwards for a day closed while « Rapports » was
 * DISABLED.
 *
 * <p>Order {@value #ORDER_OFFSET} after {@link Ordered#HIGHEST_PRECEDENCE}: after the {@code @Async} interceptor and
 * Spring Modulith's completion interceptor ({@code HIGHEST_PRECEDENCE + 10}), before the tenant binding / receipts
 * interceptor ({@link ModuleListenerInterceptor#ORDER}) — a skipped delivery takes no receipt.
 */
public class ModuleScopedListenerInterceptor implements MethodInterceptor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ModuleScopedListenerInterceptor.class);
    static final int ORDER_OFFSET = 15;
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + ORDER_OFFSET;

    private final Supplier<ModuleRegistry> registry;
    private final Map<Key, Optional<String>> modules = new ConcurrentHashMap<>();

    public ModuleScopedListenerInterceptor(Supplier<ModuleRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** Matches the event-listener methods annotated, or declared in a class annotated, {@link ModuleScoped}. */
    public static final class Pointcut extends StaticMethodMatcherPointcut {
        @Override
        public boolean matches(Method method, Class<?> targetClass) {
            return moduleOf(method, targetClass) != null;
        }
    }

    /** The module gating {@code method} of {@code targetClass}, or null when it is not a scoped listener. */
    static String moduleOf(Method method, Class<?> targetClass) {
        Class<?> userClass = targetClass != null ? ClassUtils.getUserClass(targetClass) : method.getDeclaringClass();
        Method specific = AopUtils.getMostSpecificMethod(method, userClass);
        if (!AnnotatedElementUtils.hasAnnotation(specific, EventListener.class)) {
            return null;
        }
        ModuleScoped onMethod = specific.getAnnotation(ModuleScoped.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        // the most specific class wins: the bean class, then its superclasses
        for (Class<?> c = userClass; c != null && c != Object.class; c = c.getSuperclass()) {
            ModuleScoped onClass = c.getDeclaredAnnotation(ModuleScoped.class);
            if (onClass != null) {
                return onClass.value();
            }
        }
        return null;
    }

    /** Cache key: a method is gated per target class (an inherited listener may belong to another module). */
    private record Key(Method method, Class<?> targetClass) {
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        Class<?> targetClass = target != null ? ClassUtils.getUserClass(target) : null;
        String module = modules.computeIfAbsent(new Key(method, targetClass),
                key -> Optional.ofNullable(moduleOf(key.method(), key.targetClass()))).orElse(null);
        Object[] args = invocation.getArguments();
        Object event = args.length > 0 ? args[0] : null;
        String tenant = ModuleListenerInterceptor.tenantOf(event);
        if (module == null || tenant == null || !TenantSchema.isValid(tenant)) {
            return invocation.proceed();
        }
        ModuleState state;
        try {
            state = registry.get().moduleState(tenant, module);
        } catch (RuntimeException e) {
            log.warn("[EVENTS] Module state of '{}' unreadable for tenant {} ({}) — {} delivered", module,
                    tenantCode(tenant), e.getClass().getSimpleName(), event.getClass().getSimpleName());
            return invocation.proceed();
        }
        if (state == ModuleState.DISABLED) {
            log.debug("[EVENTS] {} not delivered to {}: module '{}' disabled for tenant {}",
                    event.getClass().getSimpleName(), method.getName(), module, tenantCode(tenant));
            return null;
        }
        return invocation.proceed();
    }

    /** {@code kv_ab12cd} → {@code KV-AB12CD} (the tenant code logs name, never the schema). */
    static String tenantCode(String schema) {
        return "KV-" + schema.substring(3).toUpperCase(Locale.ROOT);
    }
}
