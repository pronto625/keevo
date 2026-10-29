package com.keevo.shared.module.application;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovery of the module-contributed beans of the core registries (Story 13.6 capability providers, Story 13.7
 * extension contributions): beans carrying a registration annotation, resolved by bean name
 * ({@link ListableBeanFactory#findAnnotationOnBean}: class-level or {@code @Bean}-method annotation, JDK or CGLIB
 * proxies). {@code scopedTarget.*} definitions are skipped (reported through their scoped proxy) and a
 * non-singleton bean (prototype, scoped) is returned with a {@link Found#problem() problem} instead of an
 * instance, so the registry fails the boot naming it. The annotation must sit directly on the bean class or on its
 * {@code @Bean} factory method: one found only on a superclass or an interface is a problem too (it would otherwise
 * register every subclass a second time).
 */
final class ModuleBeans {

    /**
     * One annotated bean.
     *
     * @param name       bean name
     * @param annotation the registration annotation (null when built from an instance that lacks it)
     * @param type       the bean type as the factory reports it (may be a proxy class)
     * @param bean       the instance; null when {@code problem} is set
     * @param problem    why the bean cannot be used at all (e.g. not a singleton), else null
     */
    record Found<A extends Annotation>(String name, A annotation, Class<?> type, Object bean, String problem) {
    }

    private ModuleBeans() {
    }

    /** The singleton beans of {@code beans} annotated {@code annotation}; {@code role} names them in problems. */
    static <A extends Annotation> List<Found<A>> collect(ListableBeanFactory beans, Class<A> annotation, String role) {
        List<Found<A>> found = new ArrayList<>();
        for (String name : beans.getBeanNamesForAnnotation(annotation)) {
            if (ScopedProxyUtils.isScopedTarget(name)) {
                continue; // the target of a scoped proxy: reported through its proxy below
            }
            A value = beans.findAnnotationOnBean(name, annotation);
            Class<?> type = beans.getType(name);
            boolean scoped = beans.containsBean(ScopedProxyUtils.getTargetBeanName(name));
            if (scoped || !beans.isSingleton(name)) {
                found.add(new Found<>(name, value, type, null, role + " bean '" + name + "' ("
                        + (type != null ? ClassUtils.getUserClass(type).getName() : "?")
                        + ") must be singleton-scoped (prototype / scoped " + role + "s are not supported)"));
                continue;
            }
            Object bean = beans.getBean(name);
            Class<?> target = ClassUtils.getUserClass(AopProxyUtils.ultimateTargetClass(bean));
            if (!declaredOn(target, annotation) && !declaredOnFactoryMethod(beans, name, annotation)) {
                found.add(new Found<>(name, value, type, null, misplaced(target, name, annotation)));
                continue;
            }
            found.add(new Found<>(name, value, type, bean, null));
        }
        return found;
    }

    /** An already-created bean (tests, tooling): its class must carry the annotation directly. */
    static <A extends Annotation> Found<A> ofInstance(Object bean, Class<A> annotation) {
        Class<?> target = ClassUtils.getUserClass(AopProxyUtils.ultimateTargetClass(bean));
        A value = AnnotatedElementUtils.findMergedAnnotation(target, annotation);
        if (value != null && !declaredOn(target, annotation)) {
            return new Found<>(target.getName(), value, bean.getClass(), null,
                    misplaced(target, target.getName(), annotation));
        }
        return new Found<>(target.getName(), value, bean.getClass(), bean, null);
    }

    private static boolean declaredOn(Class<?> type, Class<? extends Annotation> annotation) {
        return MergedAnnotations.from(type, MergedAnnotations.SearchStrategy.DIRECT).isPresent(annotation);
    }

    private static boolean declaredOnFactoryMethod(ListableBeanFactory beans, String name,
                                                   Class<? extends Annotation> annotation) {
        if (!(beans instanceof ConfigurableListableBeanFactory factory) || !factory.containsBeanDefinition(name)) {
            return false;
        }
        BeanDefinition definition = factory.getBeanDefinition(name);
        MethodMetadata method = definition instanceof AnnotatedBeanDefinition annotated
                ? annotated.getFactoryMethodMetadata() : null;
        return method != null && method.isAnnotated(annotation.getName());
    }

    private static String misplaced(Class<?> target, String name, Class<? extends Annotation> annotation) {
        String className = target.getName();
        return (className.equals(name) ? className : className + " (bean '" + name + "')") + ": @"
                + annotation.getSimpleName() + " must be declared on the bean class itself or on its @Bean method, "
                + "not inherited from a superclass or an interface";
    }

    /** Class name of the bean, plus its bean name when they differ — for boot messages and logs. */
    static String describe(Found<?> found) {
        Class<?> target = found.bean() != null ? AopProxyUtils.ultimateTargetClass(found.bean()) : found.type();
        String className = target != null ? ClassUtils.getUserClass(target).getName() : "?";
        return className.equals(found.name()) ? className : className + " (bean '" + found.name() + "')";
    }
}
