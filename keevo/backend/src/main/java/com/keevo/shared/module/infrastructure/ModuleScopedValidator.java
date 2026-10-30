package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleScoped;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import com.keevo.shared.event.catalog.TenantScopedEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Startup check of {@link ModuleScoped} (Story 13.9), naming the bean class:
 * <ul>
 *   <li><b>placement</b> (while the bean is post-processed; declared and inherited methods, class annotation on
 *       the bean class or a superclass): the annotation on a method that is not an event listener
 *       ({@code @EventListener}, {@code @TransactionalEventListener}, {@code @ApplicationModuleListener}), on a
 *       class without any event-listener method — it would gate nothing —, or a gated listener whose event parameter
 *       is not a {@code TenantScopedEvent} (no tenant to check);</li>
 *   <li><b>module id</b> (once every singleton exists; immediately for a bean created later): every id must name an
 *       installed module — a typo would silently skip (or never skip) the listener.</li>
 * </ul>
 */
@Component
public class ModuleScopedValidator implements BeanPostProcessor, SmartInitializingSingleton {

    private final ObjectProvider<ModuleRegistry> registry;
    private final Map<String, Set<String>> pending = new LinkedHashMap<>();
    private volatile boolean started;

    public ModuleScopedValidator(ObjectProvider<ModuleRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        Class<?> type = ClassUtils.getUserClass(bean);
        if (!type.getName().startsWith("com.keevo.")) {
            return bean;
        }
        Set<String> ids;
        List<String> misplaced;
        try {
            ids = moduleIds(type);
            misplaced = ids.isEmpty() ? List.of() : placementProblems(type);
        } catch (LinkageError unresolvable) {
            return bean;
        }
        if (!misplaced.isEmpty()) {
            throw new BeanCreationException(beanName, "@ModuleScoped cannot gate " + type.getName() + ": "
                    + String.join("; ", misplaced));
        }
        if (!ids.isEmpty()) {
            if (started) {
                check(Map.of(type.getName(), ids));
            } else {
                synchronized (pending) {
                    pending.merge(type.getName(), ids, (a, b) -> {
                        a.addAll(b);
                        return a;
                    });
                }
            }
        }
        return bean;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Set<String>> toCheck;
        synchronized (pending) {
            toCheck = new LinkedHashMap<>(pending);
            pending.clear();
            started = true;
        }
        check(toCheck);
    }

    /** The module ids named by {@link ModuleScoped} on the type, its superclasses and their methods. */
    static Set<String> moduleIds(Class<?> type) {
        Set<String> ids = new TreeSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            ModuleScoped onType = c.getDeclaredAnnotation(ModuleScoped.class);
            if (onType != null) {
                ids.add(onType.value());
            }
        }
        for (Method method : methods(type)) {
            ModuleScoped onMethod = method.getAnnotation(ModuleScoped.class);
            if (onMethod != null) {
                ids.add(onMethod.value());
            }
        }
        return ids;
    }

    /** Declared and inherited methods (superclasses, Object excluded), without synthetic / bridge ones. */
    private static List<Method> methods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            if (!method.isSynthetic() && !method.isBridge() && method.getDeclaringClass() != Object.class) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static boolean classScoped(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getDeclaredAnnotation(ModuleScoped.class) != null) {
                return true;
            }
        }
        return false;
    }

    static List<String> placementProblems(Class<?> type) {
        List<String> problems = new ArrayList<>();
        boolean anyListener = false;
        boolean classScoped = classScoped(type);
        for (Method method : methods(type)) {
            boolean listener = isListener(method);
            anyListener |= listener;
            boolean annotated = method.getAnnotation(ModuleScoped.class) != null;
            if (!listener && annotated) {
                problems.add(method.getName() + "() is not an event listener");
            }
            if (listener && (annotated || classScoped) && !tenantScopedEvent(method)) {
                problems.add(method.getName() + "() does not take a TenantScopedEvent: the module state cannot be "
                        + "checked per tenant");
            }
        }
        if (classScoped && !anyListener) {
            problems.add("the class declares no event-listener method");
        }
        return problems;
    }

    /** The listener's (first) parameter is a {@link TenantScopedEvent}. */
    private static boolean tenantScopedEvent(Method method) {
        return method.getParameterCount() >= 1
                && TenantScopedEvent.class.isAssignableFrom(method.getParameterTypes()[0]);
    }

    /** {@code @EventListener} or a meta-annotated listener ({@code @TransactionalEventListener}, module listener). */
    public static boolean isListener(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, EventListener.class);
    }

    private void check(Map<String, Set<String>> byClass) {
        if (byClass.isEmpty()) {
            return;
        }
        ModuleRegistry modules = registry.getObject();
        List<String> problems = new ArrayList<>();
        byClass.forEach((className, ids) -> ids.forEach(id -> {
            if (id == null || id.isBlank() || modules.definition(id).isEmpty()) {
                problems.add(className + " → '" + id + "'");
            }
        }));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("@ModuleScoped names a module that is not installed: "
                    + String.join(", ", problems));
        }
    }
}
