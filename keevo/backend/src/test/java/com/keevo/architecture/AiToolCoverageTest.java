package com.keevo.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.ai.internal.AiToolRegistryService;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.shared.module.LoadedModuleManifest;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleManifestLoader;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.8 — sibling of {@link EndpointEntitlementCoverageTest} for the AI tool layer: every {@code @Tool} method of
 * the production code
 * <ul>
 *   <li>belongs to a {@code @Component} named {@code *McpTools} in an {@code adapter.in.mcp} package (architecture
 *       rule 10), with a non-blank unique name and a description;</li>
 *   <li>is gated by {@code @RequiresEntitlement} (method, else class) on a shipped module with {@code READ} access — the
 *       layer is read-only; a write tool needs its own confirmation flow first — or carries a justified
 *       {@code @EntitlementExempt}, never both;</li>
 *   <li>names only permissions declared by a manifest;</li>
 *   <li>exposes no actor / tenant parameter: no method parameter, nor record component reachable from one, named
 *       like a tenant, schema, actor, user, role, access or scope, nor typed as a security object (the session gives
 *       them).</li>
 * </ul>
 * The tool table is checked in as {@code docs/ai/tools.md}; regenerate with
 * {@code mvn test -Dtest=AiToolCoverageTest -Dkeevo.aitools.regenerate=true} and commit it.
 */
class AiToolCoverageTest {

    static final String REGENERATE_PROPERTY = "keevo.aitools.regenerate";
    static final String TABLE = "docs/ai/tools.md";

    /** Parameter / component names that would let a caller choose the session. */
    static final Pattern FORBIDDEN_NAME = Pattern.compile(
            "(?i)^(tenant\\w*|schema\\w*|actor\\w*|user|userid|users|role|roles|access|scope|principal|"
                    + "authentication|createdby|owner(id)?|employee\\w*|seller\\w*|member\\w*)$");
    static final Set<Class<?>> FORBIDDEN_TYPES = Set.of(Authentication.class, Principal.class);

    record ToolRow(String name, String module, String access, String permission, String roles, String exemption,
                   String handler, String parameters, String description) {
    }

    @Test
    void everyToolIsGatedReadOnlyWithoutSessionParametersAndTheTableIsUpToDate() throws Exception {
        List<String> problems = new ArrayList<>();
        List<ToolRow> rows = scan(problems);
        assertThat(problems).as("AI tools without a valid declaration").isEmpty();
        assertThat(rows).as("AI tools found").hasSizeGreaterThanOrEqualTo(16);

        String expected = render(rows);
        Path table = EndpointEntitlementCoverageTest.baseDir().resolve(TABLE);
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
    void theCheckRefusesBadDeclarations() {
        Set<String> modules = Set.of("crm");
        Set<String> permissions = Set.of("INVOICE_VIEW");
        assertThat(problemsOf(Fixtures.UngatedMcpTools.class, modules, permissions)).singleElement().asString()
                .contains("UngatedMcpTools#tool", "neither");
        assertThat(problemsOf(Fixtures.WriteMcpTools.class, modules, permissions)).singleElement().asString()
                .contains("WRITE");
        assertThat(problemsOf(Fixtures.GhostMcpTools.class, modules, permissions)).anySatisfy(p ->
                assertThat(p).contains("ghost")).anySatisfy(p -> assertThat(p).contains("GHOST_PERMISSION"));
        assertThat(problemsOf(Fixtures.SessionParamMcpTools.class, modules, permissions))
                .anySatisfy(p -> assertThat(p).contains("tenantId"))
                .anySatisfy(p -> assertThat(p).contains("filter.actorId"))
                .anySatisfy(p -> assertThat(p).contains("Authentication"));
        assertThat(problemsOf(Fixtures.BothMcpTools.class, modules, permissions)).singleElement().asString()
                .contains("both");
        assertThat(problemsOf(Fixtures.Misplaced.class, modules, permissions)).isEmpty();
        assertThat(placementProblems(Fixtures.Misplaced.class))
                .anySatisfy(p -> assertThat(p).contains("adapter.in.mcp"))
                .anySatisfy(p -> assertThat(p).contains("@Component"));
        assertThat(problemsOf(Fixtures.UnnamedMcpTools.class, modules, permissions)).anySatisfy(p ->
                assertThat(p).contains("name"));
        assertThat(problemsOf(Fixtures.LeakyMcpTools.class, modules, permissions))
                .anySatisfy(p -> assertThat(p).contains("employeeId"))
                .anySatisfy(p -> assertThat(p).contains("result[].productId"))
                .anySatisfy(p -> assertThat(p).contains("neither @PreAuthorize nor @RequiresPermission"))
                .noneSatisfy(p -> assertThat(p).contains("result[].storeId"));
        assertThat(problemsOf(Fixtures.GoodMcpTools.class, modules, permissions)).isEmpty();
    }

    private static List<String> problemsOf(Class<?> type, Set<String> modules, Set<String> permissions) {
        List<String> problems = new ArrayList<>();
        inspect(type, modules, permissions, problems, new ArrayList<>(), false);
        return problems;
    }

    // ── scan ────────────────────────────────────────────────────────────────────────────────────────────────

    static List<ToolRow> scan(List<String> problems) throws Exception {
        List<ModuleManifest> manifests = ModuleManifestLoader.loadFromClasspath(new PathMatchingResourcePatternResolver())
                .stream().map(LoadedModuleManifest::manifest).toList();
        Set<String> modules = manifests.stream().map(ModuleManifest::id).collect(Collectors.toSet());
        Set<String> permissions = manifests.stream().flatMap(m -> m.permissions().stream()).collect(Collectors.toSet());
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return true; // nested and abstract classes too: a misplaced @Tool must not hide
            }
        };
        scanner.addIncludeFilter((reader, factory) -> reader.getAnnotationMetadata().hasAnnotatedMethods(
                Tool.class.getName()));
        Path mainClasses = EndpointEntitlementCoverageTest.baseDir().resolve("target/classes").toRealPath();
        List<ToolRow> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.keevo")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), AiToolCoverageTest.class.getClassLoader());
            Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!location.equals(mainClasses)) {
                continue;
            }
            List<ToolRow> found = new ArrayList<>();
            inspect(type, modules, permissions, problems, found, true);
            for (ToolRow row : found) {
                if (!names.add(row.name())) {
                    problems.add(row.handler() + ": duplicate tool name '" + row.name() + "'");
                }
            }
            rows.addAll(found);
        }
        rows.sort(Comparator.comparing(ToolRow::name));
        return rows;
    }

    /**
     * Resolves the declarations as the runtime does ({@code EntitlementAspect}, {@code AiToolRegistryService}): the
     * method's annotation, else its class'.
     *
     * @param requireComponent whether the class must be a {@code @Component} (production scan)
     */
    static void inspect(Class<?> type, Set<String> modules, Set<String> permissions, List<String> problems,
                        List<ToolRow> out, boolean requireComponent) {
        String typeName = type.getSimpleName();
        List<Method> methods = Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(type,
                        m -> m.getDeclaredAnnotation(Tool.class) != null))
                .sorted(Comparator.comparing(Method::getName)).toList();
        if (methods.isEmpty()) {
            return;
        }
        if (requireComponent) {
            problems.addAll(placementProblems(type));
        }
        for (Method method : methods) {
            String handler = typeName + "#" + method.getName();
            Tool tool = method.getDeclaredAnnotation(Tool.class);
            if (tool.name().isBlank() || !tool.name().matches("[a-z][a-z0-9_]{2,63}")) {
                problems.add(handler + ": @Tool needs an explicit snake_case name");
            }
            if (tool.description().isBlank()) {
                problems.add(handler + ": @Tool needs a (French) description");
            }
            RequiresEntitlement methodGate = method.getDeclaredAnnotation(RequiresEntitlement.class);
            EntitlementExempt methodExempt = method.getDeclaredAnnotation(EntitlementExempt.class);
            RequiresEntitlement gate = methodGate != null ? methodGate
                    : methodExempt != null ? null : type.getDeclaredAnnotation(RequiresEntitlement.class);
            EntitlementExempt exempt = methodExempt != null ? methodExempt
                    : methodGate != null ? null : type.getDeclaredAnnotation(EntitlementExempt.class);
            if (methodGate != null && methodExempt != null) {
                problems.add(handler + ": both @RequiresEntitlement and @EntitlementExempt");
                continue;
            }
            if (gate == null && exempt == null) {
                problems.add(handler + ": neither @RequiresEntitlement nor @EntitlementExempt (method or class)");
                continue;
            }
            if (gate != null && gate.access() != RequiresEntitlement.Access.READ) {
                problems.add(handler + ": AI tools are read-only — gate READ (found " + gate.access() + ")");
            }
            if (gate != null && !modules.contains(gate.value())) {
                problems.add(handler + ": unknown module '" + gate.value() + "'");
            }
            if (exempt != null && exempt.value().isBlank()) {
                problems.add(handler + ": @EntitlementExempt needs a justification");
            }
            RequiresPermission permission = AnnotationUtils.findAnnotation(method, RequiresPermission.class);
            if (permission == null) {
                permission = AnnotationUtils.findAnnotation(type, RequiresPermission.class);
            }
            if (permission != null) {
                for (String p : permission.value()) {
                    if (!permissions.contains(p)) {
                        problems.add(handler + ": permission '" + p + "' is declared by no manifest");
                    }
                }
            }
            PreAuthorize roleGate = AnnotationUtils.findAnnotation(method, PreAuthorize.class);
            if (roleGate == null) {
                roleGate = AnnotationUtils.findAnnotation(type, PreAuthorize.class);
            }
            if (roleGate == null && permission == null) {
                problems.add(handler + ": neither @PreAuthorize nor @RequiresPermission — mirror the REST handler's "
                        + "role / permission gate");
            }
            checkNoTechnicalId(handler, "result", method.getGenericReturnType(), problems, new HashSet<>());
            for (Parameter parameter : method.getParameters()) {
                checkSessionFree(handler, parameter.getName(), parameter.getParameterizedType(), problems,
                        new HashSet<>());
            }
            PreAuthorize role = AnnotationUtils.findAnnotation(method, PreAuthorize.class);
            if (role == null) {
                role = AnnotationUtils.findAnnotation(type, PreAuthorize.class);
            }
            String parameters = "";
            try {
                ToolDefinition definition = ToolDefinitions.from(method);
                parameters = parameters(definition.inputSchema());
            } catch (RuntimeException e) {
                problems.add(handler + ": no JSON schema could be generated (" + e.getMessage() + ")");
            }
            out.add(new ToolRow(tool.name(), gate != null ? gate.value() : "", gate != null ? gate.access().name() : "",
                    permission != null ? String.join(", ", permission.value()) : "", role != null ? role.value() : "",
                    exempt != null && gate == null ? exempt.value() : "", handler, parameters, tool.description()));
        }
    }

    /** Architecture rule 10 (Story 21.8): a {@code @Component} named {@code *McpTools} in {@code adapter.in.mcp}. */
    static List<String> placementProblems(Class<?> type) {
        List<String> problems = new ArrayList<>();
        if (!AiToolRegistryService.isToolBeanType(type)) {
            problems.add(type.getSimpleName()
                    + ": @Tool methods must live in a *McpTools class of an adapter.in.mcp package");
        }
        if (AnnotationUtils.findAnnotation(type, Component.class) == null) {
            problems.add(type.getSimpleName() + ": a *McpTools class must be a @Component");
        }
        return problems;
    }

    /** A parameter, and every record component reachable from it, must not name or carry the session. */
    static void checkSessionFree(String handler, String path, Type type, List<String> problems, Set<Class<?>> seen) {
        String leaf = path.substring(path.lastIndexOf('.') + 1);
        if (FORBIDDEN_NAME.matcher(leaf).matches()) {
            problems.add(handler + ": parameter '" + path + "' names the session (tenant / actor / role) — "
                    + "the registry reads it from the security and tenant contexts");
        }
        Class<?> raw = type instanceof Class<?> c ? c
                : type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> r ? r : null;
        if (raw == null) {
            return;
        }
        for (Class<?> forbidden : FORBIDDEN_TYPES) {
            if (forbidden.isAssignableFrom(raw)) {
                problems.add(handler + ": parameter '" + path + "' is a " + forbidden.getSimpleName());
            }
        }
        if (type instanceof ParameterizedType p) {
            for (Type argument : p.getActualTypeArguments()) {
                checkSessionFree(handler, path + "[]", argument, problems, seen);
            }
        }
        if (raw.isRecord() && seen.add(raw)) {
            for (RecordComponent component : raw.getRecordComponents()) {
                checkSessionFree(handler, path + "." + component.getName(), component.getGenericType(), problems,
                        seen);
            }
        }
    }

    /**
     * A result carries no technical id: no {@code UUID} anywhere in the returned records (lists included) but a
     * component named {@code storeId}, the documented chaining key.
     */
    static void checkNoTechnicalId(String handler, String path, Type type, List<String> problems, Set<Class<?>> seen) {
        Class<?> raw = type instanceof Class<?> c ? c
                : type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> r ? r : null;
        if (raw == null) {
            return;
        }
        if (raw == UUID.class && !path.endsWith(".storeId")) {
            problems.add(handler + ": " + path + " is a technical id (only storeId may be returned)");
        }
        if (type instanceof ParameterizedType p) {
            for (Type argument : p.getActualTypeArguments()) {
                checkNoTechnicalId(handler, path + "[]", argument, problems, seen);
            }
        }
        if (raw.isRecord() && seen.add(raw)) {
            for (RecordComponent component : raw.getRecordComponents()) {
                checkNoTechnicalId(handler, path + "." + component.getName(), component.getGenericType(), problems,
                        seen);
            }
        }
    }

    /** {@code name*} (required) and {@code name} (optional), in schema order. */
    static String parameters(String schema) {
        try {
            JsonNode node = new ObjectMapper().readTree(schema);
            Set<String> required = new HashSet<>();
            node.path("required").forEach(n -> required.add(n.asText()));
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.path("properties").fieldNames(); it.hasNext(); ) {
                String name = it.next();
                names.add(required.contains(name) ? name + "*" : name);
            }
            return names.isEmpty() ? "—" : String.join(", ", names);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable tool schema", e);
        }
    }

    static String render(List<ToolRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AI tools → module entitlement\n\n")
                .append("Generated by `AiToolCoverageTest` (Story 21.8) — do not edit by hand.\n")
                .append("Regenerate: `cd keevo/backend && mvn test -Dtest=AiToolCoverageTest -D")
                .append(REGENERATE_PROPERTY).append("=true`.\n\n")
                .append("Read-only tools of the AI layer (`shared.ai.AiToolRegistry`), consumed by the in-app assistant ")
                .append("(21.4) and the external MCP server (21.9). Each tool runs for the session's actor and tenant ")
                .append("(never a parameter) through the same gates as the REST handler it mirrors: role rule ")
                .append("(`@PreAuthorize`), permission (`@RequiresPermission`), module (`@RequiresEntitlement`, READ), ")
                .append("then the REST store / employee scope. Refusals come back as `FORBIDDEN`, ")
                .append("`MODULE_NOT_ENTITLED` / `MODULE_DISABLED_BY_OWNER` with the REST French message. Every call ")
                .append("is audited in `public.ai_tool_calls` (tool, channel, actor, outcome, duration — never the ")
                .append("arguments or the result). Parameters marked `*` are required. `storeId` (from `list_stores` ")
                .append("or `stock_by_store`) is the only id a tool returns or takes; it is never shown to users.\n\n");
        sb.append("Tools: ").append(rows.size()).append(".\n\n");
        sb.append("| Tool | Module | Access | Permission | Roles | Parameters | Handler |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (ToolRow r : rows) {
            sb.append("| `").append(r.name()).append("` | ")
                    .append(r.module().isEmpty() ? "— (" + r.exemption() + ")" : "`" + r.module() + "`").append(" | ")
                    .append(r.access().isEmpty() ? "—" : r.access()).append(" | ")
                    .append(r.permission().isEmpty() ? "—" : "`" + r.permission() + "`").append(" | ")
                    .append(r.roles().isEmpty() ? "—" : "`" + r.roles() + "`").append(" | ")
                    .append(r.parameters()).append(" | ")
                    .append(r.handler()).append(" |\n");
        }
        sb.append("\n## Descriptions\n\n");
        for (ToolRow r : rows) {
            sb.append("- `").append(r.name()).append("` — ").append(r.description()).append('\n');
        }
        return sb.toString();
    }

    /** Fixtures for the self-check (not Spring beans; {@code inspect} is called with requireComponent = false). */
    static final class Fixtures {

        record Filter(String search, UUID actorId) {
        }

        static class UngatedMcpTools {
            @Tool(name = "ungated", description = "x")
            public String tool() {
                return "";
            }
        }

        static class WriteMcpTools {
            @Tool(name = "write_tool", description = "x")
            @PreAuthorize("hasRole('OWNER')")
            @RequiresEntitlement("crm")
            public String tool() {
                return "";
            }
        }

        static class GhostMcpTools {
            @Tool(name = "ghost_tool", description = "x")
            @RequiresPermission("GHOST_PERMISSION")
            @RequiresEntitlement(value = "ghost", access = RequiresEntitlement.Access.READ)
            public String tool() {
                return "";
            }
        }

        static class SessionParamMcpTools {
            @Tool(name = "session_tool", description = "x")
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            public String tool(String tenantId, Filter filter, Authentication auth) {
                return "";
            }
        }

        static class BothMcpTools {
            @Tool(name = "both_tool", description = "x")
            @PreAuthorize("hasRole('OWNER')")
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            @EntitlementExempt("test")
            public String tool() {
                return "";
            }
        }

        static class Misplaced {
            @Tool(name = "misplaced_tool", description = "x")
            @PreAuthorize("hasRole('OWNER')")
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            public String tool() {
                return "";
            }
        }

        static class UnnamedMcpTools {
            @Tool(description = "x")
            @PreAuthorize("hasRole('OWNER')")
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            public String tool() {
                return "";
            }
        }

        record Row(UUID storeId, String name, UUID productId) {
        }

        static class LeakyMcpTools {
            @Tool(name = "leaky_tool", description = "x")
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            public List<Row> tool(UUID employeeId) {
                return List.of();
            }
        }

        static class GoodMcpTools {
            @Tool(name = "good_tool", description = "Un bon outil")
            @RequiresPermission("INVOICE_VIEW")
            @RequiresEntitlement(value = "crm", access = RequiresEntitlement.Access.READ)
            public String tool(String search, Integer limit) {
                return "";
            }
        }
    }
}
