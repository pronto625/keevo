package com.keevo.architecture;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.PackageMatcher;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Keevo-specific architecture rules (spec-socle §13 rules 4-7, Story 12.6b) — the invariants Spring Modulith
 * {@code verify()} does not cover. Each rule returns its violations as {@code key -> detail}; the key is what a
 * line of the rule's allow-list ({@code src/test/resources/architecture/<id>-allowlist.txt}) names.
 * Rule (d) — acyclic {@code requires} graph — lives in {@link RequiresGraphAcyclicityTest} (Epic 13).
 */
final class KeevoArchitectureRules {

    enum Rule {
        TENANT_SCHEMA_JDBC("tenant-schema-jdbc",
            "(a) classes running JdbcTemplate/NamedParameterJdbcTemplate SQL and reading a tenant schema must call "
                + "TenantSchema.validate/isValid"),
        PERSISTENCE_LOCATION("persistence-location",
            "(b) @Entity, @Embeddable, @MappedSuperclass, @Repository and Spring Data Repository types only under "
                + "..adapter.out.persistence.."),
        WEB_FREE_APPLICATION("web-free-application",
            "(c) no org.springframework.web / org.springframework.http / jakarta.servlet types in ..domain.. or "
                + "..application.."),
        TRANSACTIONAL_PUBLISHERS("transactional-publishers",
            "(e) methods publishing an event consumed after commit (@ApplicationModuleListener, or "
                + "@TransactionalEventListener AFTER_* without fallbackExecution) run inside a transaction "
                + "(@Transactional method/class, TransactionTemplate, or only called from such methods)"),
        CAPABILITY_INJECTION("capability-injection",
            "(f) no field or constructor parameter typed as a Capability sub-interface outside shared.module: "
                + "capabilities are resolved per tenant through CapabilityRegistry.find (Story 13.6)");

        final String id;
        final String description;

        Rule(String id, String description) {
            this.id = id;
            this.description = description;
        }
    }

    static final String TENANT_SCHEMA = "com.keevo.shared.infrastructure.persistence.TenantSchema";
    static final String TENANT_CONTEXT = "com.keevo.shared.infrastructure.persistence.TenantContext";

    private static final Pattern SCHEMA_NAME = Pattern.compile("(?i).*schema.*");
    private static final List<PackageMatcher> APPLICATION_PACKAGES = List.of(
        PackageMatcher.of("..domain.."),
        PackageMatcher.of("..application.."));
    private static final List<String> WEB_PACKAGES =
        List.of("org.springframework.web", "org.springframework.http", "jakarta.servlet");
    private static final List<Class<? extends Annotation>> PERSISTENCE_ANNOTATIONS = List.of(
        jakarta.persistence.Entity.class,
        jakarta.persistence.Embeddable.class,
        jakarta.persistence.MappedSuperclass.class,
        org.springframework.stereotype.Repository.class);
    private static final PackageMatcher PERSISTENCE_ADAPTER = PackageMatcher.of("..adapter.out.persistence..");

    private KeevoArchitectureRules() {
    }

    static SortedMap<String, String> violations(Rule rule, JavaClasses classes) {
        return switch (rule) {
            case TENANT_SCHEMA_JDBC -> tenantSchemaJdbc(classes);
            case PERSISTENCE_LOCATION -> persistenceLocation(classes);
            case WEB_FREE_APPLICATION -> webFreeApplication(classes);
            case TRANSACTIONAL_PUBLISHERS -> transactionalPublishers(classes);
            case CAPABILITY_INJECTION -> capabilityInjection(classes);
        };
    }

    // ── (f) capabilities never injected ─────────────────────────────────────────────────────────────

    static final String CAPABILITY = "com.keevo.shared.module.Capability";
    private static final PackageMatcher SHARED_MODULE = PackageMatcher.of("com.keevo.shared.module..");

    /**
     * A field or constructor parameter typed as a {@code Capability} sub-interface would bypass the tenant check of
     * {@code CapabilityRegistry.find} (the providers are not autowire candidates either, so it also fails at
     * startup). Key: the declaring class.
     */
    static SortedMap<String, String> capabilityInjection(JavaClasses classes) {
        SortedMap<String, Set<String>> found = new TreeMap<>();
        for (JavaClass javaClass : classes) {
            if (SHARED_MODULE.matches(javaClass.getPackageName())) {
                continue;
            }
            javaClass.getFields().forEach(field -> {
                if (isCapabilitySubtype(field.getRawType())) {
                    found.computeIfAbsent(javaClass.getName(), k -> new TreeSet<>())
                        .add("field " + field.getName() + " : " + field.getRawType().getSimpleName());
                }
            });
            javaClass.getConstructors().forEach(constructor -> constructor.getRawParameterTypes().forEach(type -> {
                if (isCapabilitySubtype(type)) {
                    found.computeIfAbsent(javaClass.getName(), k -> new TreeSet<>())
                        .add("constructor parameter : " + type.getSimpleName());
                }
            }));
        }
        SortedMap<String, String> violations = new TreeMap<>();
        found.forEach((name, where) -> violations.put(name, "injects " + where + " — use CapabilityRegistry.find"));
        return violations;
    }

    private static boolean isCapabilitySubtype(JavaClass type) {
        return !type.getName().equals(CAPABILITY) && type.isAssignableTo(CAPABILITY);
    }

    // ── (a) tenant schema validation ────────────────────────────────────────────────────────────────

    /**
     * Static approximation of "a schema concatenated into SQL is validated": per top-level class (nested and
     * anonymous classes such as row mappers are folded into it), if it calls a JDBC template and reads a
     * tenant schema — {@code TenantContext.getCurrentTenant()}, a String-returning method or a String field
     * whose name contains "schema" — it must call {@code TenantSchema.validate}/{@code isValid}.
     *
     * <p><b>AC (a) is enforced by a pair:</b> this class-level ArchUnit rule and the line-level source scan
     * {@code TenantSchemaInterpolationGuardTest} (every interpolation must be validated nearby). This rule alone
     * misses a schema arriving as a method parameter, and an allow-list entry exempts the whole class from it —
     * the guard test still covers allow-listed classes line by line.
     */
    static SortedMap<String, String> tenantSchemaJdbc(JavaClasses classes) {
        SortedMap<String, Set<String>> jdbc = new TreeMap<>();
        SortedMap<String, Set<String>> schemaSources = new TreeMap<>();
        Set<String> guarded = new HashSet<>();
        for (JavaClass javaClass : classes) {
            String top = topLevel(javaClass).getName();
            for (JavaCall<?> call : javaClass.getCodeUnitCallsFromSelf()) {
                JavaClass owner = call.getTargetOwner();
                String name = call.getName();
                if (isJdbc(owner)) {
                    jdbc.computeIfAbsent(top, k -> new TreeSet<>()).add(owner.getSimpleName() + "." + name);
                } else if (owner.getName().equals(TENANT_SCHEMA)) {
                    if (name.equals("validate") || name.equals("isValid")) {
                        guarded.add(top);
                    }
                } else if (isSchemaRead(call)) {
                    schemaSources.computeIfAbsent(top, k -> new TreeSet<>()).add(owner.getSimpleName() + "." + name + "()");
                }
            }
            for (JavaFieldAccess access : javaClass.getFieldAccessesFromSelf()) {
                if (access.getAccessType() == JavaFieldAccess.AccessType.GET
                        && SCHEMA_NAME.matcher(access.getName()).matches()
                        && access.getTarget().getRawType().isEquivalentTo(String.class)) {
                    schemaSources.computeIfAbsent(top, k -> new TreeSet<>()).add("field " + access.getName());
                }
            }
        }
        SortedMap<String, String> violations = new TreeMap<>();
        jdbc.forEach((top, calls) -> {
            Set<String> sources = schemaSources.get(top);
            if (sources != null && !guarded.contains(top)) {
                violations.put(top, "runs " + calls + ", reads a schema via " + sources
                    + ", never calls TenantSchema.validate/isValid");
            }
        });
        return violations;
    }

    private static boolean isJdbc(JavaClass owner) {
        return owner.isAssignableTo(JdbcOperations.class)
            || owner.isAssignableTo(NamedParameterJdbcOperations.class)
            || owner.isAssignableTo(JdbcClient.class);
    }

    private static boolean isSchemaRead(JavaCall<?> call) {
        if (!(call instanceof JavaMethodCall methodCall)) {
            return false;
        }
        if (call.getTargetOwner().getName().equals(TENANT_CONTEXT)) {
            return call.getName().equals("getCurrentTenant");
        }
        return SCHEMA_NAME.matcher(call.getName()).matches()
            && methodCall.getTarget().getRawReturnType().isEquivalentTo(String.class);
    }

    // ── (b) persistence types location ──────────────────────────────────────────────────────────────

    static SortedMap<String, String> persistenceLocation(JavaClasses classes) {
        SortedMap<String, String> violations = new TreeMap<>();
        for (JavaClass javaClass : classes) {
            if (PERSISTENCE_ADAPTER.matches(javaClass.getPackageName())) {
                continue;
            }
            Optional<String> annotation = PERSISTENCE_ANNOTATIONS.stream()
                .filter(javaClass::isAnnotatedWith)
                .map(Class::getSimpleName)
                .findFirst();
            if (annotation.isPresent()) {
                violations.put(javaClass.getName(), "@" + annotation.get() + " in " + javaClass.getPackageName());
            } else if (javaClass.isAssignableTo(org.springframework.data.repository.Repository.class)) {
                violations.put(javaClass.getName(), "Spring Data Repository in " + javaClass.getPackageName());
            }
        }
        return violations;
    }

    // ── (c) no web types in the application layer ───────────────────────────────────────────────────

    static SortedMap<String, String> webFreeApplication(JavaClasses classes) {
        SortedMap<String, Set<String>> found = new TreeMap<>();
        for (JavaClass javaClass : classes) {
            String pkg = javaClass.getPackageName();
            if (APPLICATION_PACKAGES.stream().noneMatch(m -> m.matches(pkg))) {
                continue;
            }
            javaClass.getDirectDependenciesFromSelf().forEach(dependency -> {
                JavaClass target = dependency.getTargetClass().getBaseComponentType();
                if (WEB_PACKAGES.stream().anyMatch(p -> target.getPackageName().equals(p)
                        || target.getPackageName().startsWith(p + "."))) {
                    found.computeIfAbsent(topLevel(javaClass).getName(), k -> new TreeSet<>()).add(target.getName());
                }
            });
        }
        SortedMap<String, String> violations = new TreeMap<>();
        found.forEach((k, v) -> violations.put(k, "depends on " + v));
        return violations;
    }

    // ── (e) transactional publishers of after-commit-listened events ────────────────────────────────

    /**
     * Event types consumed after commit — first parameter of an {@code @ApplicationModuleListener}, or of a
     * {@code @TransactionalEventListener} with phase AFTER_COMMIT / AFTER_COMPLETION / AFTER_ROLLBACK and
     * {@code fallbackExecution = false}: a publication outside a transaction never reaches those listeners.
     * A method "publishes" such an event when it calls {@code ApplicationEventPublisher.publishEvent} and constructs
     * the event (or calls a method returning it). It runs in a transaction when it, its class (or a superclass /
     * interface, incl. the same method declared on an interface) is annotated or meta-annotated with
     * {@code @Transactional} (so {@code @ApplicationModuleListener} counts), when it calls
     * {@code TransactionOperations}, or when every static caller path reaches such a method (diamonds and recursion
     * handled: a revisit is neutral).
     *
     * <p><b>Accepted blind spots:</b> any {@code TransactionOperations} call in the method counts, even when the
     * publication sits outside the callback (lambda bodies are attributed to the enclosing method); a publication
     * through a generic helper or port ({@code publish(Object)}) is not detected; {@code @Transactional}
     * propagation ({@code NOT_SUPPORTED}, {@code NEVER}) and proxy self-invocation are ignored; callers reached
     * only through polymorphic dispatch or a method reference are not seen (the method is then treated as an entry
     * point — allow-list it with the reason).
     */
    static SortedMap<String, String> transactionalPublishers(JavaClasses classes) {
        Set<String> listened = classes.stream()
            .flatMap(c -> c.getMethods().stream())
            .filter(m -> !m.getRawParameterTypes().isEmpty() && consumesAfterCommit(m))
            .map(m -> m.getRawParameterTypes().get(0).getName())
            .collect(Collectors.toSet());

        Map<JavaCodeUnit, Boolean> memo = new HashMap<>();
        SortedMap<String, String> violations = new TreeMap<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.getModifiers().contains(JavaModifier.SYNTHETIC)
                        || method.getModifiers().contains(JavaModifier.BRIDGE)) {
                    continue;
                }
                boolean publishes = method.getMethodCallsFromSelf().stream()
                    .anyMatch(c -> c.getName().equals("publishEvent")
                        && c.getTargetOwner().isAssignableTo(ApplicationEventPublisher.class));
                if (!publishes) {
                    continue;
                }
                Set<String> published = new TreeSet<>();
                for (JavaConstructorCall call : method.getConstructorCallsFromSelf()) {
                    if (listened.contains(call.getTargetOwner().getName())) {
                        published.add(call.getTargetOwner().getSimpleName());
                    }
                }
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    JavaClass returned = call.getTarget().getRawReturnType();
                    if (listened.contains(returned.getName())) {
                        published.add(returned.getSimpleName());
                    }
                }
                if (!published.isEmpty() && !runsInTransaction(method, memo)) {
                    violations.put(key(method), "publishes " + published + " outside a transaction");
                }
            }
        }
        return violations;
    }

    /** {@code com.x.Cls#apply(SyncOperation,UUID,String)} — raw parameter simple names, so overloads differ. */
    static String key(JavaMethod method) {
        return method.getOwner().getName() + "#" + method.getName() + method.getRawParameterTypes().stream()
            .map(JavaClass::getSimpleName)
            .collect(Collectors.joining(",", "(", ")"));
    }

    private static boolean consumesAfterCommit(JavaMethod method) {
        if (method.isAnnotatedWith(ApplicationModuleListener.class)) {
            return true;
        }
        return method.tryGetAnnotationOfType(TransactionalEventListener.class)
            .map(l -> l.phase() != TransactionPhase.BEFORE_COMMIT && !l.fallbackExecution())
            .orElse(false);
    }

    /**
     * Backward reachability from {@code method} through non-transactional callers: false as soon as a
     * non-transactional entry point (no static caller) is reached. A method already seen in this walk is
     * neutral (diamond, recursion); results are memoised per method.
     */
    private static boolean runsInTransaction(JavaCodeUnit method, Map<JavaCodeUnit, Boolean> memo) {
        Boolean cached = memo.get(method);
        if (cached != null) {
            return cached;
        }
        Deque<JavaCodeUnit> todo = new ArrayDeque<>();
        todo.push(method);
        Set<JavaCodeUnit> seen = new HashSet<>();
        boolean result = true;
        while (!todo.isEmpty() && result) {
            JavaCodeUnit unit = todo.pop();
            if (!seen.add(unit) || isTransactional(unit)) {
                continue;
            }
            Boolean known = unit == method ? null : memo.get(unit);
            if (known != null) {
                result = known;
                continue;
            }
            Set<? extends JavaCall<?>> callers = unit.getCallsOfSelf();
            if (callers.isEmpty()) {
                result = false;
            }
            callers.forEach(c -> todo.push(c.getOrigin()));
        }
        memo.put(method, result);
        return result;
    }

    private static boolean isTransactional(JavaCodeUnit method) {
        if (hasTransactional(method) || method.getMethodCallsFromSelf().stream()
                .anyMatch(c -> c.getTargetOwner().isAssignableTo(TransactionOperations.class))) {
            return true;
        }
        JavaClass owner = method.getOwner();
        if (hasTransactional(owner) || owner.getAllRawSuperclasses().stream().anyMatch(KeevoArchitectureRules::hasTransactional)) {
            return true;
        }
        String[] parameters = method.getRawParameterTypes().stream().map(JavaClass::getName).toArray(String[]::new);
        return owner.getAllRawInterfaces().stream().anyMatch(i -> hasTransactional(i)
            || i.tryGetMethod(method.getName(), parameters).map(KeevoArchitectureRules::hasTransactional).orElse(false));
    }

    private static boolean hasTransactional(HasAnnotations<?> element) {
        return element.isAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            || element.isMetaAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            || element.isAnnotatedWith(jakarta.transaction.Transactional.class)
            || element.isMetaAnnotatedWith(jakarta.transaction.Transactional.class);
    }

    private static JavaClass topLevel(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }
}
