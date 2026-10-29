package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.RequiresEntitlement;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Startup check of {@link RequiresEntitlement} (Story 13.5). Two failures, both naming the bean class:
 * <ol>
 *   <li><b>placement</b> (immediately, while the bean is post-processed): a placement {@link EntitlementAspect}'s
 *       pointcut cannot advise would leave the gate silently open, so it is refused —
 *       <ul>
 *         <li>the annotation on a supertype, an interface, or a supertype / interface method (only the bean class
 *             itself and the methods it declares are matched);</li>
 *         <li>on a method that is not public, or is static, final or private (not proxied);</li>
 *         <li>a class-level annotation on a class inheriting public methods it does not override (inherited
 *             methods are not matched by {@code @within}), or declaring a public final method;</li>
 *         <li>on a method the pointcut excludes ({@code @ExceptionHandler}, {@code @InitBinder},
 *             {@code @ModelAttribute});</li>
 *         <li>on — or, class-level, on a class declaring — a {@code @Scheduled} or event-listener method
 *             ({@code @EventListener}, {@code @TransactionalEventListener}, {@code @ApplicationModuleListener}):
 *             those run without a tenant bound and would always throw;</li>
 *       </ul></li>
 *   <li><b>module id</b> (once every singleton exists, the registry being then available; immediately for a bean
 *       created later): every id must name an installed module — a typo would refuse every call at runtime.</li>
 * </ol>
 */
@Component
public class RequiresEntitlementValidator implements BeanPostProcessor, SmartInitializingSingleton {

    private static final List<Class<? extends Annotation>> EXCLUDED_HANDLER_METHODS =
            List.of(ExceptionHandler.class, InitBinder.class, ModelAttribute.class);
    private static final List<Class<? extends Annotation>> TENANTLESS_METHODS =
            List.of(Scheduled.class, Schedules.class, EventListener.class);

    private final ObjectProvider<ModuleRegistry> registry;
    /** class name → module ids it names, until the singletons are all created. */
    private final Map<String, Set<String>> pending = new LinkedHashMap<>();
    private volatile boolean started;

    public RequiresEntitlementValidator(ObjectProvider<ModuleRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        Class<?> type = ClassUtils.getUserClass(bean);
        if (isPlatformType(type)) {
            return bean; // JDK / Jakarta / Spring types cannot carry the annotation
        }
        List<String> misplaced;
        try {
            misplaced = placementProblems(type);
        } catch (LinkageError unresolvable) {
            return bean; // a class whose optional dependencies are absent: it cannot reference the annotation either
        }
        if (!misplaced.isEmpty()) {
            throw new BeanCreationException(beanName, "@RequiresEntitlement cannot be enforced on "
                    + type.getName() + ": " + String.join("; ", misplaced));
        }
        Set<String> ids = moduleIds(type);
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

    /** The module ids named by {@link RequiresEntitlement} directly on the type and on the methods it declares. */
    static Set<String> moduleIds(Class<?> type) {
        Set<String> ids = new TreeSet<>();
        RequiresEntitlement onType = type.getDeclaredAnnotation(RequiresEntitlement.class);
        if (onType != null) {
            ids.add(onType.value());
        }
        for (Method method : type.getDeclaredMethods()) {
            RequiresEntitlement onMethod = method.getAnnotation(RequiresEntitlement.class);
            if (onMethod != null && !method.isSynthetic() && !method.isBridge()) {
                ids.add(onMethod.value());
            }
        }
        return ids;
    }

    /** Every placement of the annotation on {@code type}'s hierarchy that the aspect would not enforce. */
    static List<String> placementProblems(Class<?> type) {
        List<String> problems = new ArrayList<>();
        Set<Class<?>> supertypes = new LinkedHashSet<>();
        for (Class<?> c = type.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            supertypes.add(c);
        }
        supertypes.addAll(ClassUtils.getAllInterfacesForClassAsSet(type));
        for (Class<?> supertype : supertypes) {
            if (isPlatformType(supertype)) {
                continue;
            }
            if (supertype.getDeclaredAnnotation(RequiresEntitlement.class) != null) {
                problems.add("annotation on supertype " + supertype.getName() + " is not enforced — put it on the "
                        + "bean class");
            }
            for (Method method : supertype.getDeclaredMethods()) {
                if (method.getAnnotation(RequiresEntitlement.class) != null) {
                    problems.add("annotation on " + supertype.getSimpleName() + "." + method.getName()
                            + "() is not enforced — put it on the bean class's own method");
                }
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()
                    || method.getAnnotation(RequiresEntitlement.class) == null) {
                continue;
            }
            int mod = method.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
                problems.add(method.getName() + "() must be public, non-static and non-final to be gated");
            }
            if (hasAny(method, EXCLUDED_HANDLER_METHODS)) {
                problems.add(method.getName() + "() is MVC handler infrastructure, never gated");
            }
            if (hasAny(method, TENANTLESS_METHODS)) {
                problems.add(method.getName() + "() is a scheduled / event-listener method, run without a tenant");
            }
        }
        if (type.getDeclaredAnnotation(RequiresEntitlement.class) != null) {
            for (Method method : type.getMethods()) {
                Class<?> declaring = method.getDeclaringClass();
                if (declaring != type && declaring != Object.class && !Modifier.isStatic(method.getModifiers())) {
                    problems.add("class-level annotation does not gate " + method.getName() + "() inherited from "
                            + declaring.getName() + " — override it or annotate the declaring class's bean");
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                int mod = method.getModifiers();
                if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(mod)) {
                    continue;
                }
                if (Modifier.isPublic(mod) && Modifier.isFinal(mod)) {
                    problems.add("public final " + method.getName() + "() cannot be gated");
                }
                if (hasAny(method, TENANTLESS_METHODS)) {
                    problems.add("class-level annotation on a bean declaring the scheduled / event-listener method "
                            + method.getName() + "(), which runs without a tenant");
                }
            }
        }
        return problems;
    }

    private static boolean isPlatformType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || name.startsWith("org.springframework.");
    }

    private static boolean hasAny(Method method, List<Class<? extends Annotation>> annotations) {
        return annotations.stream().anyMatch(a -> AnnotatedElementUtils.hasAnnotation(method, a));
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
            throw new IllegalStateException("@RequiresEntitlement names a module that is not installed: "
                    + String.join(", ", problems));
        }
    }
}
