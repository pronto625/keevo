package com.keevo.architecture;

import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.10a — every REST handler of the application (each method of a production {@code @Controller} — so
 * {@code @RestController} too —, declared or inherited, mapped
 * with {@code @RequestMapping} or a composed variant) declares how it is gated:
 * {@link RequiresEntitlement} (module id + access) or {@link EntitlementExempt} (reason), on the method or on its
 * class (the method wins), never both at the same level. Access follows the HTTP verb: {@code GET} → {@code READ},
 * any other verb → {@code WRITE}. Module ids must be shipped manifests.
 *
 * <p>The resulting endpoint → entitlement table is checked in as {@code docs/entitlements/endpoints.md} (module
 * base dir) and must match the code. After changing a controller, regenerate it with
 * {@code mvn test -Dtest=EndpointEntitlementCoverageTest -Dkeevo.entitlements.regenerate=true} and commit the file.
 */
class EndpointEntitlementCoverageTest {

    static final String REGENERATE_PROPERTY = "keevo.entitlements.regenerate";
    static final String TABLE = "docs/entitlements/endpoints.md";

    record Endpoint(String verb, String path, String handler, String module, String access, String exemption,
                    String permission) {
    }

    @Test
    void everyHandlerIsGatedOrExemptAndTheTableIsUpToDate() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Endpoint> endpoints = scan(problems);
        assertThat(problems).as("REST handlers without a valid entitlement declaration").isEmpty();
        assertThat(endpoints).as("REST handlers found").hasSizeGreaterThan(100);

        String expected = render(endpoints);
        Path table = baseDir().resolve(TABLE);
        if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
            Files.createDirectories(table.getParent());
            Files.writeString(table, expected, StandardCharsets.UTF_8);
        }
        assertThat(table).as("%s missing — regenerate with -D%s=true", TABLE, REGENERATE_PROPERTY).exists();
        assertThat(Files.readString(table, StandardCharsets.UTF_8))
                .as("%s is out of date — regenerate with -D%s=true and commit it", TABLE, REGENERATE_PROPERTY)
                .isEqualTo(expected);
    }

    @Test
    void theCheckRefusesMissingDoubleAndMismatchedDeclarations() {
        Set<String> modules = Set.of("crm");
        assertThat(problemsOf(Fixtures.Missing.class, modules)).singleElement().asString().contains("Missing#get", "neither");
        assertThat(problemsOf(Fixtures.Both.class, modules)).singleElement().asString().contains("Both#get", "both");
        assertThat(problemsOf(Fixtures.WrongAccess.class, modules)).singleElement().asString()
                .contains("WrongAccess#post", "WRITE");
        assertThat(problemsOf(Fixtures.UnknownModule.class, modules)).singleElement().asString()
                .contains("UnknownModule#get", "ghost");
        // 13.10a review: a method-level exemption does not switch off a class-level gate at runtime
        assertThat(problemsOf(Fixtures.ExemptUnderGatedClass.class, modules)).singleElement().asString()
                .contains("ExemptUnderGatedClass#get", "class-level @RequiresEntitlement");
        // READ on a non-GET verb only with a justification; a justification only there
        assertThat(problemsOf(Fixtures.ReadPostWithoutJustification.class, modules)).singleElement().asString()
                .contains("ReadPostWithoutJustification#post", "readJustification");
        assertThat(problemsOf(Fixtures.JustificationOnGet.class, modules)).singleElement().asString()
                .contains("JustificationOnGet#get", "readJustification");
        assertThat(problemsOf(Fixtures.ReadPostJustified.class, modules)).isEmpty();
        // 14.1: a permission must be declared by a manifest
        List<String> permissionProblems = new ArrayList<>();
        List<Endpoint> permissioned = new ArrayList<>();
        inspect(Fixtures.WithPermission.class, modules, Set.of("INVOICE_VIEW"), permissionProblems, permissioned);
        assertThat(permissionProblems).singleElement().asString().contains("WithPermission#post", "GHOST_PERMISSION");
        assertThat(permissioned).extracting(Endpoint::permission).containsExactly("INVOICE_VIEW", "GHOST_PERMISSION");
        // inherited handler: declared by the unannotated superclass, the subclass' class-level gate does not advise it
        assertThat(problemsOf(Fixtures.InheritsHandler.class, modules)).singleElement().asString()
                .contains("InheritsHandler#get", "inherited");
        // plain @Controller with @ResponseBody handlers is scanned like a @RestController
        List<Endpoint> plain = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        inspect(Fixtures.PlainController.class, modules, problems, plain);
        assertThat(problems).singleElement().asString().contains("PlainController#get", "neither");

        List<Endpoint> ok = new ArrayList<>();
        problems.clear();
        inspect(Fixtures.ClassLevel.class, modules, problems, ok);
        assertThat(problems).isEmpty();
        assertThat(ok).extracting(Endpoint::path, Endpoint::exemption, Endpoint::module)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/x", "test", ""),
                        org.assertj.core.groups.Tuple.tuple("/x/y", "", "crm"));
    }

    private static List<String> problemsOf(Class<?> type, Set<String> modules) {
        List<String> problems = new ArrayList<>();
        inspect(type, modules, problems, new ArrayList<>());
        return problems;
    }

    // ── scan ────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every production {@code @Controller} (so {@code @RestController} too): any mapped handler method is an
     * endpoint, whether it answers a body through {@code @ResponseBody} or not.
     */
    static List<Endpoint> scan(List<String> problems) throws Exception {
        List<com.keevo.shared.module.ModuleManifest> manifests = ModuleManifestLoader.loadFromClasspath(
                new PathMatchingResourcePatternResolver()).stream().map(LoadedModuleManifest::manifest).toList();
        Set<String> modules = manifests.stream().map(m -> m.id()).collect(Collectors.toSet());
        Set<String> permissions = manifests.stream().flatMap(m -> m.permissions().stream())
                .collect(Collectors.toSet());
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Path mainClasses = baseDir().resolve("target/classes").toRealPath();
        List<Endpoint> endpoints = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.keevo")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), EndpointEntitlementCoverageTest.class.getClassLoader());
            Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!location.equals(mainClasses)) {
                continue; // test-only controllers (slice tests) are not part of the application
            }
            inspect(type, modules, permissions, problems, endpoints);
        }
        endpoints.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::verb)
                .thenComparing(Endpoint::handler));
        return endpoints;
    }

    /**
     * Resolves the declarations exactly as {@code EntitlementAspect} does at runtime: the annotation on the executed
     * (most specific) method, else on its declaring class, else on the bean class — {@code getDeclaredAnnotation},
     * no inheritance. Handlers inherited from a superclass are inspected too.
     */
    static void inspect(Class<?> type, Set<String> modules, List<String> problems, List<Endpoint> out) {
        inspect(type, modules, null, problems, out);
    }

    /**
     * @param permissions Story 14.1 — the permissions declared by the manifests ({@code null}: not checked); a
     *                    {@code @RequiresPermission} (method, else class) naming another one is a problem
     */
    static void inspect(Class<?> type, Set<String> modules, Set<String> permissions, List<String> problems,
                        List<Endpoint> out) {
        RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String[] prefixes = typeMapping != null && typeMapping.path().length > 0 ? typeMapping.path() : new String[]{""};
        String typeName = type.getSimpleName();
        if (type.getDeclaredAnnotation(RequiresEntitlement.class) != null
                && type.getDeclaredAnnotation(EntitlementExempt.class) != null) {
            problems.add(typeName + ": both @RequiresEntitlement and @EntitlementExempt on the class");
        }
        List<Method> methods = Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(type,
                        m -> m.getDeclaringClass() != Object.class && !m.isSynthetic() && !m.isBridge()))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
        for (Method method : methods) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String handler = typeName + "#" + method.getName();
            Class<?> declaring = method.getDeclaringClass();
            RequiresEntitlement methodGate = method.getDeclaredAnnotation(RequiresEntitlement.class);
            EntitlementExempt methodExempt = method.getDeclaredAnnotation(EntitlementExempt.class);
            RequiresEntitlement classGate = declaring.getDeclaredAnnotation(RequiresEntitlement.class);
            if (classGate == null) {
                classGate = type.getDeclaredAnnotation(RequiresEntitlement.class);
            }
            EntitlementExempt classExempt = declaring.getDeclaredAnnotation(EntitlementExempt.class);
            if (classExempt == null) {
                classExempt = type.getDeclaredAnnotation(EntitlementExempt.class);
            }
            if (methodGate != null && methodExempt != null) {
                problems.add(handler + ": both @RequiresEntitlement and @EntitlementExempt on the method");
                continue;
            }
            if (methodExempt != null && classGate != null) {
                problems.add(handler + ": method-level @EntitlementExempt under a class-level @RequiresEntitlement — "
                        + "the runtime aspect still gates it (@within)");
                continue;
            }
            if (declaring != type && methodGate == null
                    && declaring.getDeclaredAnnotation(RequiresEntitlement.class) == null
                    && type.getDeclaredAnnotation(RequiresEntitlement.class) != null) {
                problems.add(handler + ": inherited handler is not advised by the subclass' class-level "
                        + "@RequiresEntitlement — override it");
                continue;
            }
            RequiresEntitlement gate = methodGate != null ? methodGate : methodExempt != null ? null : classGate;
            EntitlementExempt exempt = methodExempt != null ? methodExempt : methodGate != null ? null : classExempt;
            if (gate == null && exempt == null) {
                problems.add(handler + ": neither @RequiresEntitlement nor @EntitlementExempt (method or class)");
                continue;
            }
            RequestMethod[] verbs = mapping.method();
            String verb = verbs.length == 0 ? "ANY" : Arrays.stream(verbs).map(Enum::name).sorted()
                    .collect(Collectors.joining(","));
            String access = "";
            if (gate != null) {
                boolean justified = !gate.readJustification().isBlank();
                if ("GET".equals(verb)) {
                    if (gate.access() != RequiresEntitlement.Access.READ) {
                        problems.add(handler + ": GET must be gated READ (found " + gate.access() + ")");
                    }
                    if (justified) {
                        problems.add(handler + ": readJustification only applies to a non-GET handler gated READ");
                    }
                } else if (gate.access() == RequiresEntitlement.Access.READ && !justified) {
                    problems.add(handler + ": " + verb + " gated READ needs a readJustification (else WRITE)");
                } else if (gate.access() == RequiresEntitlement.Access.WRITE && justified) {
                    problems.add(handler + ": readJustification only applies to a non-GET handler gated READ");
                }
                if (!modules.contains(gate.value())) {
                    problems.add(handler + ": unknown module '" + gate.value() + "'");
                }
                access = gate.access().name();
                if (justified) {
                    access += " (" + gate.readJustification() + ")";
                }
            }
            RequiresPermission permissionGate = method.getDeclaredAnnotation(RequiresPermission.class);
            if (permissionGate == null) {
                permissionGate = declaring.getDeclaredAnnotation(RequiresPermission.class);
            }
            if (permissionGate == null) {
                permissionGate = type.getDeclaredAnnotation(RequiresPermission.class);
            }
            String permission = "";
            if (permissionGate != null) {
                if (permissionGate.value().length == 0) {
                    problems.add(handler + ": @RequiresPermission without a permission");
                }
                for (String p : permissionGate.value()) {
                    if (permissions != null && !permissions.contains(p)) {
                        problems.add(handler + ": permission '" + p + "' is declared by no manifest");
                    }
                }
                permission = String.join(", ", permissionGate.value());
            }
            String[] paths = mapping.path().length > 0 ? mapping.path() : new String[]{""};
            for (String prefix : prefixes) {
                for (String path : paths) {
                    out.add(new Endpoint(verb, join(prefix, path), handler, gate != null ? gate.value() : "",
                            access, exempt != null && gate == null ? exempt.value() : "", permission));
                }
            }
        }
    }

    private static String join(String prefix, String path) {
        String p = (prefix.isEmpty() || prefix.startsWith("/") ? prefix : "/" + prefix);
        if (path.isEmpty()) {
            return p.isEmpty() ? "/" : p;
        }
        return p + (path.startsWith("/") ? path : "/" + path);
    }

    static String render(List<Endpoint> endpoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("# REST endpoints → module entitlement\n\n")
                .append("Generated by `EndpointEntitlementCoverageTest` (Story 13.10a) — do not edit by hand.\n")
                .append("Regenerate: `cd keevo/backend && mvn test -Dtest=EndpointEntitlementCoverageTest -D")
                .append(REGENERATE_PROPERTY).append("=true`.\n\n")
                .append("Gated handlers run only when the tenant's module state allows it (`READ`: enabled or read-only; ")
                .append("`WRITE`: enabled) — otherwise 403 `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY`. ")
                .append("Exempt handlers state why. ")
                .append("A permission (Story 14.1, `@RequiresPermission`, from the module manifests) is checked first: ")
                .append("missing → 403 `FORBIDDEN`; `OWNER` holds every permission, `EMPLOYEE` those its module grants, ")
                .append("`SUPER_ADMIN` none.\n\n");
        long gated = endpoints.stream().filter(e -> !e.module().isEmpty()).count();
        sb.append("Handlers: ").append(endpoints.size()).append(" — gated: ").append(gated)
                .append(", exempt: ").append(endpoints.size() - gated).append(".\n\n");
        sb.append("| Method | Path | Module | Access | Permission | Exemption | Handler |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (Endpoint e : endpoints) {
            sb.append("| ").append(e.verb()).append(" | `").append(e.path()).append("` | ")
                    .append(e.module().isEmpty() ? "—" : "`" + e.module() + "`").append(" | ")
                    .append(e.access().isEmpty() ? "—" : e.access()).append(" | ")
                    .append(e.permission().isEmpty() ? "—" : "`" + e.permission() + "`").append(" | ")
                    .append(e.exemption().isEmpty() ? "" : e.exemption()).append(" | ")
                    .append(e.handler()).append(" |\n");
        }
        return sb.toString();
    }

    /** Module base dir from this class's location ({@code target/test-classes} → {@code ../..}), not the CWD. */
    static Path baseDir() throws URISyntaxException {
        return Path.of(EndpointEntitlementCoverageTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().getParent();
    }

    /** Controllers for the self-check of {@link #inspect} (not Spring beans: not annotated {@code @RestController}). */
    static final class Fixtures {

        @RequestMapping("/p")
        static class WithPermission {
            @RequiresPermission("INVOICE_VIEW")
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            @org.springframework.web.bind.annotation.GetMapping
            public void get() {
            }

            @RequiresPermission("GHOST_PERMISSION")
            @RequiresEntitlement("crm")
            @org.springframework.web.bind.annotation.PostMapping
            public void post() {
            }
        }

        @RequestMapping("/x")
        static class Missing {
            @org.springframework.web.bind.annotation.GetMapping
            public void get() {
            }
        }

        @RequestMapping("/x")
        static class Both {
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            @EntitlementExempt("test")
            @org.springframework.web.bind.annotation.GetMapping
            public void get() {
            }
        }

        @RequestMapping("/x")
        static class WrongAccess {
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.WRITE)
            @org.springframework.web.bind.annotation.GetMapping
            public void post() {
            }
        }

        @RequiresEntitlement("crm")
        @RequestMapping("/x")
        static class ExemptUnderGatedClass {
            @EntitlementExempt("test")
            @org.springframework.web.bind.annotation.GetMapping
            public void get() {
            }
        }

        @RequestMapping("/x")
        static class ReadPostWithoutJustification {
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            @org.springframework.web.bind.annotation.PostMapping
            public void post() {
            }
        }

        @RequestMapping("/x")
        static class JustificationOnGet {
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ, readJustification = "x")
            @org.springframework.web.bind.annotation.GetMapping
            public void get() {
            }
        }

        @RequestMapping("/x")
        static class ReadPostJustified {
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ, readJustification = "lit")
            @org.springframework.web.bind.annotation.PostMapping
            public void post() {
            }
        }

        static class UnannotatedBase {
            @org.springframework.web.bind.annotation.GetMapping("/inherited")
            public void get() {
            }
        }

        @RequiresEntitlement("crm")
        @RequestMapping("/x")
        static class InheritsHandler extends UnannotatedBase {
        }

        @Controller
        @RequestMapping("/x")
        static class PlainController {
            @org.springframework.web.bind.annotation.ResponseBody
            @org.springframework.web.bind.annotation.GetMapping
            public String get() {
                return "";
            }
        }

        @RequestMapping("/x")
        static class UnknownModule {
            @RequiresEntitlement(value = "ghost", access = RequiresEntitlement.Access.READ)
            @org.springframework.web.bind.annotation.GetMapping
            public void get() {
            }
        }

        @EntitlementExempt("test")
        @RequestMapping("x")
        static class ClassLevel {
            @org.springframework.web.bind.annotation.GetMapping
            public void a() {
            }

            @RequiresEntitlement("crm")
            @org.springframework.web.bind.annotation.PostMapping("y")
            public void b() {
            }

            public void notAHandler() {
            }
        }
    }
}
