package com.keevo.shared.module.infrastructure;

import com.keevo.shared.module.RequiresPermission;
import com.keevo.shared.module.application.ModulePermissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Story 14.1 — startup check of {@link RequiresPermission}: every value must be a permission declared by an
 * installed manifest ({@code module.json} {@code permissions}), else the application does not start (a typo would
 * refuse every call at runtime). Checked once every singleton exists; immediately for a bean created later.
 */
@Component
public class RequiresPermissionValidator implements BeanPostProcessor, SmartInitializingSingleton {

    private final ObjectProvider<ModulePermissions> permissions;
    private final Map<String, Set<String>> pending = new LinkedHashMap<>();
    private volatile boolean started;

    public RequiresPermissionValidator(ObjectProvider<ModulePermissions> permissions) {
        this.permissions = permissions;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        Class<?> type = ClassUtils.getUserClass(bean);
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.") || name.startsWith("org.springframework.")) {
            return bean;
        }
        Set<String> values;
        try {
            values = permissionValues(type);
        } catch (LinkageError unresolvable) {
            return bean;
        }
        if (values.isEmpty()) {
            return bean;
        }
        if (started) {
            check(Map.of(name, values));
        } else {
            synchronized (pending) {
                pending.merge(name, values, (a, b) -> {
                    a.addAll(b);
                    return a;
                });
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

    /** The permissions named by {@link RequiresPermission} on the type and on the methods it declares. */
    static Set<String> permissionValues(Class<?> type) {
        Set<String> values = new TreeSet<>();
        RequiresPermission onType = type.getDeclaredAnnotation(RequiresPermission.class);
        if (onType != null) {
            values.addAll(List.of(onType.value()));
            if (onType.value().length == 0) {
                values.add("");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            RequiresPermission onMethod = method.getAnnotation(RequiresPermission.class);
            if (onMethod != null && !method.isSynthetic() && !method.isBridge()) {
                values.addAll(List.of(onMethod.value()));
                if (onMethod.value().length == 0) {
                    values.add("");
                }
            }
        }
        return values;
    }

    private void check(Map<String, Set<String>> byClass) {
        if (byClass.isEmpty()) {
            return;
        }
        ModulePermissions declared = permissions.getObject();
        List<String> problems = new ArrayList<>();
        byClass.forEach((className, values) -> values.forEach(value -> {
            if (!declared.isDeclared(value)) {
                problems.add(className + " → '" + value + "'");
            }
        }));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("@RequiresPermission names a permission no installed module declares: "
                    + String.join(", ", problems));
        }
    }
}
