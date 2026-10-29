package com.keevo.shared.module;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads and validates the module manifests (Story 13.1, spec-socle §3).
 *
 * <p>Manifests live at {@code keevo/modules/<id>/module.json} on the classpath ({@link #CLASSPATH_PATTERN}): the
 * application is one jar, so a single root {@code module.json} per module is impossible, and the {@code keevo/}
 * prefix keeps a dependency jar shipping its own {@code modules/} directory out of the catalogue. Validation is
 * all-or-nothing — every problem is collected and reported in one {@link InvalidModuleManifestException}:
 * <ul>
 *   <li>per file: well-formed JSON object (no duplicate keys); {@code id}, {@code version}, {@code displayName},
 *       {@code category} present and non-blank; {@code id} matches {@link #ID_PATTERN} and its directory;
 *       {@code version} is {@code MAJOR.MINOR.PATCH}; {@code category} ∈ core|business|premium|platform
 *       ({@code core} for the module {@code core}); list fields are arrays of distinct non-blank strings,
 *       {@code requires}/{@code enhances} entries are module ids, a module neither enhances itself nor lists the
 *       same id in both; {@code free} is a boolean; {@code entitlement} matches {@link #ENTITLEMENT_PATTERN}
 *       (default {@code module.<id>}); {@code provides} entries match {@link #PROVIDES_PATTERN} and {@code needs} entries
 *       {@link #NEEDS_PATTERN} (a capability simple name, {@code ?} suffix = optional need), no name both hard and
 *       optional — Story 13.6;</li>
 *   <li>across the files that parsed (always checked, even when another file is invalid): unique ids and
 *       entitlements; exactly one {@code core} module, which requires nothing; every other module requires
 *       {@code core}; every {@code requires} entry is a known module; the {@code requires} graph is acyclic
 *       (every cycle is listed, one per strongly connected component); {@code provides} names are unique across
 *       manifests; a hard {@code needs} entry (no {@code ?}) is provided by a module of the consumer's transitive
 *       {@code requires} closure (Story 13.6 — an optional need may be absent: WARN when nobody provides it).</li>
 * </ul>
 * Unknown {@code enhances} ids are allowed (a soft dependency may target a module that is not installed) and
 * only logged at WARN. Unknown keys are logged at WARN (typo detection) and dropped: the stored JSON
 * ({@link LoadedModuleManifest#json()}) is the validated manifest with every default applied.
 */
public final class ModuleManifestLoader {

    private static final Logger log = LoggerFactory.getLogger(ModuleManifestLoader.class);

    /** Where the manifests are looked up (every classpath root). */
    public static final String CLASSPATH_PATTERN = "classpath*:keevo/modules/*/module.json";

    /** Module id syntax: lower-case words separated by single hyphens, at most {@value #ID_MAX} characters. */
    public static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    public static final int ID_MAX = 50;

    /** Entitlement key syntax. */
    public static final Pattern ENTITLEMENT_PATTERN = Pattern.compile("^module\\.[a-z0-9-]+$");

    /** {@code needs} entry syntax: a capability simple name ending in {@code Port}, {@code ?} = optional. */
    public static final Pattern NEEDS_PATTERN = Pattern.compile("^[A-Z][A-Za-z0-9]*Port\\??$");

    /** {@code provides} entry syntax: a capability simple name ending in {@code Port}. */
    public static final Pattern PROVIDES_PATTERN = Pattern.compile("^[A-Z][A-Za-z0-9]*Port$");

    static final Pattern SEMVER = Pattern.compile("^(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})$");
    static final int DISPLAY_NAME_MAX = 100;

    private static final String MANIFEST_FILE = "module.json";
    private static final List<String> LIST_FIELDS = List.of(
            "requires", "enhances", "publishes", "consumes", "provides", "needs", "permissions", "routes");
    private static final Set<String> KNOWN_KEYS = Set.of("id", "version", "displayName", "category", "requires",
            "enhances", "publishes", "consumes", "provides", "needs", "extensionPoints", "permissions", "routes",
            "entitlement", "free");
    private static final Set<String> EXTENSION_POINT_KEYS = Set.of("declares", "fills");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ModuleManifestLoader() {
    }

    /** Loads and validates every manifest matching {@link #CLASSPATH_PATTERN} through {@code resolver}. */
    public static List<LoadedModuleManifest> loadFromClasspath(ResourcePatternResolver resolver) {
        List<ModuleManifestSource> sources = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(CLASSPATH_PATTERN)) {
                String location = resource.getURL().toString();
                try (InputStream in = resource.getInputStream()) {
                    sources.add(new ModuleManifestSource(location, directoryOf(location), in.readAllBytes()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the module manifests (" + CLASSPATH_PATTERN + ")", e);
        }
        return load(sources);
    }

    /**
     * Validates {@code sources} and returns the manifests sorted by id.
     *
     * @throws InvalidModuleManifestException listing every problem found
     */
    public static List<LoadedModuleManifest> load(List<ModuleManifestSource> sources) {
        List<String> problems = new ArrayList<>();
        if (sources.isEmpty()) {
            problems.add("no module manifest found (" + CLASSPATH_PATTERN + ")");
        }
        List<LoadedModuleManifest> loaded = new ArrayList<>();
        Set<String> invalidDirectories = new HashSet<>();
        for (ModuleManifestSource source : sources) {
            Optional<LoadedModuleManifest> parsed = parse(source, problems);
            parsed.ifPresentOrElse(loaded::add, () -> invalidDirectories.add(source.directory()));
        }
        if (!sources.isEmpty()) {
            validateGraph(loaded, invalidDirectories, problems);
        }
        if (!problems.isEmpty()) {
            throw new InvalidModuleManifestException(problems);
        }
        loaded.sort(Comparator.comparing(m -> m.manifest().id()));
        return List.copyOf(loaded);
    }

    /**
     * First cycle of a {@code requires} graph found by depth-first search, as the path {@code [x, …, x]}; ids
     * absent from the keys are leaves.
     */
    public static Optional<List<String>> findRequiresCycle(Map<String, List<String>> graph) {
        Map<String, Integer> state = new HashMap<>(); // 1 = on stack, 2 = done
        for (String start : graph.keySet()) {
            Optional<List<String>> cycle = visit(start, graph, state, new ArrayList<>());
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    /**
     * Every independent cycle of a {@code requires} graph: one cycle path {@code [x, …, x]} per strongly connected
     * component that contains a cycle (more than one node, or a self-loop), sorted by first node.
     */
    public static List<List<String>> findRequiresCycles(Map<String, List<String>> graph) {
        List<List<String>> cycles = new ArrayList<>();
        for (Set<String> component : stronglyConnectedComponents(graph)) {
            String only = component.iterator().next();
            if (component.size() == 1 && !graph.getOrDefault(only, List.of()).contains(only)) {
                continue;
            }
            Map<String, List<String>> sub = new LinkedHashMap<>();
            component.stream().sorted().forEach(node -> sub.put(node,
                    graph.getOrDefault(node, List.of()).stream().filter(component::contains).toList()));
            findRequiresCycle(sub).ifPresent(cycles::add);
        }
        cycles.sort(Comparator.comparing(c -> c.get(0)));
        return cycles;
    }

    // ── per file ────────────────────────────────────────────────────────────────────────────────────────

    private static Optional<LoadedModuleManifest> parse(ModuleManifestSource source, List<String> problems) {
        String at = source.location();
        JsonNode root;
        try {
            root = MAPPER.readTree(source.content());
        } catch (JsonProcessingException e) {
            problems.add(at + ": malformed JSON — " + e.getOriginalMessage());
            return Optional.empty();
        } catch (IOException e) {
            problems.add(at + ": unreadable — " + e.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            problems.add(at + ": malformed manifest — the document must be a JSON object");
            return Optional.empty();
        }
        warnUnknownKeys(root, KNOWN_KEYS, "", at);
        int before = problems.size();

        String id = requiredString(root, "id", at, problems);
        if (id != null) {
            if (!isModuleId(id)) {
                problems.add(at + ": field 'id' = '" + id + "' must match " + ID_PATTERN.pattern()
                        + " (at most " + ID_MAX + " characters)");
            }
            if (source.directory() != null && !id.equals(source.directory())) {
                problems.add(at + ": field 'id' = '" + id + "' does not match its directory 'keevo/modules/"
                        + source.directory() + "/'");
            }
        }
        String version = requiredString(root, "version", at, problems);
        if (version != null && !SEMVER.matcher(version).matches()) {
            problems.add(at + ": field 'version' = '" + version + "' is not a MAJOR.MINOR.PATCH semver");
        }
        String displayName = requiredString(root, "displayName", at, problems);
        if (displayName != null && displayName.length() > DISPLAY_NAME_MAX) {
            problems.add(at + ": field 'displayName' is longer than " + DISPLAY_NAME_MAX + " characters");
        }
        String categoryCode = requiredString(root, "category", at, problems);
        ModuleCategory category = null;
        if (categoryCode != null) {
            category = ModuleCategory.fromCode(categoryCode).orElse(null);
            if (category == null) {
                problems.add(at + ": field 'category' = '" + categoryCode
                        + "' must be one of core|business|premium|platform");
            } else if (ModuleManifest.CORE_ID.equals(id) && category != ModuleCategory.CORE) {
                problems.add(at + ": field 'category' — module 'core' must have category 'core', found '"
                        + categoryCode + "'");
            }
        }
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (String field : LIST_FIELDS) {
            lists.put(field, stringArray(root.get(field), "'" + field + "'", at, problems));
        }
        for (String field : List.of("requires", "enhances")) {
            for (String ref : lists.get(field)) {
                if (!isModuleId(ref)) {
                    problems.add(at + ": field '" + field + "' entry '" + ref + "' is not a valid module id");
                }
            }
        }
        for (String provided : lists.get("provides")) {
            if (!PROVIDES_PATTERN.matcher(provided).matches()) {
                problems.add(at + ": field 'provides' entry '" + provided + "' must match "
                        + PROVIDES_PATTERN.pattern() + " (a capability name ending in 'Port')");
            }
        }
        for (String need : lists.get("needs")) {
            if (!NEEDS_PATTERN.matcher(need).matches()) {
                problems.add(at + ": field 'needs' entry '" + need + "' must match " + NEEDS_PATTERN.pattern()
                        + " (a capability name ending in 'Port', '?' suffix for an optional need)");
            } else if (!need.endsWith("?") && lists.get("needs").contains(need + "?")) {
                problems.add(at + ": field 'needs' lists '" + need + "' both as a hard and an optional need");
            }
        }
        if (id != null && lists.get("enhances").contains(id)) {
            problems.add(at + ": field 'enhances' — module '" + id + "' cannot enhance itself");
        }
        for (String both : lists.get("requires")) {
            if (lists.get("enhances").contains(both)) {
                problems.add(at + ": fields 'requires' and 'enhances' both list '" + both + "'");
            }
        }
        ModuleManifest.ExtensionPoints extensionPoints = extensionPoints(root.get("extensionPoints"), at, problems);
        String entitlement = optionalString(root, "entitlement", at, problems);
        if (entitlement != null && !ENTITLEMENT_PATTERN.matcher(entitlement).matches()) {
            problems.add(at + ": field 'entitlement' = '" + entitlement + "' must match " + ENTITLEMENT_PATTERN.pattern());
        }
        boolean free = false;
        JsonNode freeNode = root.get("free");
        if (freeNode != null && !freeNode.isNull()) {
            if (freeNode.isBoolean()) {
                free = freeNode.booleanValue();
            } else {
                problems.add(at + ": field 'free' must be a boolean");
            }
        }

        if (problems.size() > before) {
            return Optional.empty();
        }
        ModuleManifest manifest = new ModuleManifest(id, version, displayName, category,
                lists.get("requires"), lists.get("enhances"), lists.get("publishes"), lists.get("consumes"),
                lists.get("provides"), lists.get("needs"), extensionPoints, lists.get("permissions"),
                lists.get("routes"), entitlement != null ? entitlement : "module." + id, free);
        return Optional.of(new LoadedModuleManifest(at, manifest, toJson(manifest)));
    }

    private static boolean isModuleId(String value) {
        return value.length() <= ID_MAX && ID_PATTERN.matcher(value).matches();
    }

    private static void warnUnknownKeys(JsonNode node, Set<String> known, String prefix, String at) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name)) {
                log.warn("Module manifest {}: unknown key '{}{}' ignored (typo?)", at, prefix, name);
            }
        }
    }

    private static String requiredString(JsonNode root, String field, String at, List<String> problems) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            problems.add(at + ": required field '" + field + "' is missing");
            return null;
        }
        if (!node.isTextual()) {
            problems.add(at + ": field '" + field + "' must be a string");
            return null;
        }
        if (node.textValue().isBlank()) {
            problems.add(at + ": required field '" + field + "' is blank");
            return null;
        }
        return node.textValue();
    }

    private static String optionalString(JsonNode root, String field, String at, List<String> problems) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.textValue().isBlank()) {
            problems.add(at + ": field '" + field + "' must be a non-blank string");
            return null;
        }
        return node.textValue();
    }

    private static List<String> stringArray(JsonNode node, String field, String at, List<String> problems) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            problems.add(at + ": field " + field + " must be a JSON array of strings");
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode element = node.get(i);
            if (!element.isTextual() || element.textValue().isBlank()) {
                problems.add(at + ": field " + field + "[" + i + "] must be a non-blank string");
            } else if (!values.add(element.textValue())) {
                problems.add(at + ": field " + field + " lists '" + element.textValue() + "' more than once");
            }
        }
        return List.copyOf(values);
    }

    private static ModuleManifest.ExtensionPoints extensionPoints(JsonNode node, String at, List<String> problems) {
        if (node == null || node.isNull()) {
            return ModuleManifest.ExtensionPoints.NONE;
        }
        if (!node.isObject()) {
            problems.add(at + ": field 'extensionPoints' must be an object {declares: [], fills: []}");
            return ModuleManifest.ExtensionPoints.NONE;
        }
        warnUnknownKeys(node, EXTENSION_POINT_KEYS, "extensionPoints.", at);
        return new ModuleManifest.ExtensionPoints(
                stringArray(node.get("declares"), "'extensionPoints.declares'", at, problems),
                stringArray(node.get("fills"), "'extensionPoints.fills'", at, problems));
    }

    /** The validated manifest, every default applied, in a stable key order (the {@code manifest} JSONB cache). */
    static String toJson(ModuleManifest m) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", m.id());
        root.put("version", m.version());
        root.put("displayName", m.displayName());
        root.put("category", m.category().code());
        array(root, "requires", m.requires());
        array(root, "enhances", m.enhances());
        array(root, "publishes", m.publishes());
        array(root, "consumes", m.consumes());
        array(root, "provides", m.provides());
        array(root, "needs", m.needs());
        ObjectNode extensionPoints = root.putObject("extensionPoints");
        array(extensionPoints, "declares", m.extensionPoints().declares());
        array(extensionPoints, "fills", m.extensionPoints().fills());
        array(root, "permissions", m.permissions());
        array(root, "routes", m.routes());
        root.put("entitlement", m.entitlement());
        root.put("free", m.free());
        return root.toString();
    }

    private static void array(ObjectNode parent, String field, List<String> values) {
        ArrayNode array = parent.putArray(field);
        values.forEach(array::add);
    }

    // ── across files ────────────────────────────────────────────────────────────────────────────────────

    private static void validateGraph(List<LoadedModuleManifest> loaded, Set<String> invalidDirectories,
                                      List<String> problems) {
        Map<String, LoadedModuleManifest> byId = new LinkedHashMap<>();
        Map<String, LoadedModuleManifest> byEntitlement = new HashMap<>();
        for (LoadedModuleManifest m : loaded) {
            LoadedModuleManifest previous = byId.putIfAbsent(m.manifest().id(), m);
            if (previous != null) {
                problems.add("duplicate module id '" + m.manifest().id() + "' in " + previous.location()
                        + " and " + m.location());
                continue;
            }
            LoadedModuleManifest sameEntitlement = byEntitlement.putIfAbsent(m.manifest().entitlement(), m);
            if (sameEntitlement != null) {
                problems.add("duplicate entitlement '" + m.manifest().entitlement() + "' in "
                        + sameEntitlement.location() + " and " + m.location());
            }
        }
        if (!byId.containsKey(ModuleManifest.CORE_ID)) {
            problems.add("no valid module with id '" + ModuleManifest.CORE_ID
                    + "' (exactly one core module is required)");
        }
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (LoadedModuleManifest m : byId.values()) {
            ModuleManifest manifest = m.manifest();
            if (manifest.isCore() && !manifest.requires().isEmpty()) {
                problems.add(m.location() + ": field 'requires' — module 'core' must not require other modules, found "
                        + manifest.requires());
            }
            if (!manifest.isCore() && !manifest.requires().contains(ModuleManifest.CORE_ID)) {
                problems.add(m.location() + ": field 'requires' — module '" + manifest.id()
                        + "' must require '" + ModuleManifest.CORE_ID + "'");
            }
            List<String> known = new ArrayList<>();
            for (String required : manifest.requires()) {
                if (byId.containsKey(required)) {
                    known.add(required);
                } else if (invalidDirectories.contains(required)) {
                    problems.add(m.location() + ": field 'requires' — module '" + manifest.id()
                            + "' requires '" + required + "', whose manifest is invalid (see above)");
                } else {
                    problems.add(m.location() + ": field 'requires' — module '" + manifest.id()
                            + "' requires unknown module '" + required + "'");
                }
            }
            graph.put(manifest.id(), known);
            for (String enhanced : manifest.enhances()) {
                if (!byId.containsKey(enhanced)) {
                    log.warn("Module manifest {}: module '{}' enhances '{}', which is not installed (soft dependency, ignored)",
                            m.location(), manifest.id(), enhanced);
                }
            }
        }
        for (List<String> cycle : findRequiresCycles(graph)) {
            problems.add("cycle in the 'requires' graph: " + String.join(" -> ", cycle));
        }
        validateCapabilities(byId, graph, problems);
    }

    /** Story 13.6 — unique {@code provides} names; hard {@code needs} met within the {@code requires} closure. */
    private static void validateCapabilities(Map<String, LoadedModuleManifest> byId, Map<String, List<String>> graph,
                                             List<String> problems) {
        Map<String, LoadedModuleManifest> providers = new HashMap<>();
        for (LoadedModuleManifest m : byId.values()) {
            for (String provided : m.manifest().provides()) {
                LoadedModuleManifest previous = providers.putIfAbsent(provided, m);
                if (previous != null) {
                    problems.add("capability '" + provided + "' provided by two modules: '"
                            + previous.manifest().id() + "' (" + previous.location() + ") and '"
                            + m.manifest().id() + "' (" + m.location() + ")");
                }
            }
        }
        for (LoadedModuleManifest m : byId.values()) {
            ModuleManifest manifest = m.manifest();
            Set<String> closure = null;
            for (String need : manifest.needs()) {
                if (need.endsWith("?")) { // optional: resolved at runtime, may be absent
                    String name = need.substring(0, need.length() - 1);
                    if (!providers.containsKey(name)) {
                        log.warn("Module manifest {}: module '{}' optionally needs '{}', which no installed module "
                                + "provides (always absent)", m.location(), manifest.id(), name);
                    }
                    continue;
                }
                if (closure == null) {
                    closure = requiresClosure(manifest.id(), graph);
                }
                LoadedModuleManifest provider = providers.get(need);
                if (provider == null || !closure.contains(provider.manifest().id())) {
                    problems.add(m.location() + ": field 'needs' — module '" + manifest.id() + "' needs '" + need
                            + "' (hard need, no '?') but no module of its 'requires' closure " + closure
                            + " provides it" + (provider == null ? " (no installed module provides it)"
                            : " (provided by '" + provider.manifest().id() + "': add it to 'requires' or "
                            + "mark the need optional '" + need + "?')"));
                }
            }
        }
    }

    /** Modules reachable through {@code requires} from {@code id} (excluding {@code id}), sorted. */
    private static Set<String> requiresClosure(String id, Map<String, List<String>> graph) {
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(graph.getOrDefault(id, List.of()));
        while (!todo.isEmpty()) {
            String next = todo.pop();
            if (!next.equals(id) && seen.add(next)) {
                todo.addAll(graph.getOrDefault(next, List.of()));
            }
        }
        return new java.util.TreeSet<>(seen);
    }

    private static Optional<List<String>> visit(String node, Map<String, List<String>> graph,
                                                Map<String, Integer> state, List<String> path) {
        Integer s = state.get(node);
        if (s != null && s == 2) {
            return Optional.empty();
        }
        if (s != null) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
            cycle.add(node);
            return Optional.of(cycle);
        }
        state.put(node, 1);
        path.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            Optional<List<String>> cycle = visit(next, graph, state, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        state.put(node, 2);
        return Optional.empty();
    }

    /** Tarjan's algorithm (recursive; the graph has a few dozen nodes at most). */
    private static List<Set<String>> stronglyConnectedComponents(Map<String, List<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        int[] counter = {0};
        for (String node : graph.keySet()) {
            if (!index.containsKey(node)) {
                strongConnect(node, graph, index, low, stack, onStack, components, counter);
            }
        }
        return components;
    }

    private static void strongConnect(String node, Map<String, List<String>> graph, Map<String, Integer> index,
                                      Map<String, Integer> low, Deque<String> stack, Set<String> onStack,
                                      List<Set<String>> components, int[] counter) {
        index.put(node, counter[0]);
        low.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            if (!index.containsKey(next)) {
                strongConnect(next, graph, index, low, stack, onStack, components, counter);
                low.put(node, Math.min(low.get(node), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(node, Math.min(low.get(node), index.get(next)));
            }
        }
        if (low.get(node).equals(index.get(node))) {
            Set<String> component = new LinkedHashSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            components.add(component);
        }
    }

    /** {@code .../modules/<dir>/module.json} → {@code <dir>}; null when the location has another shape. */
    static String directoryOf(String location) {
        String normalised = location.replace('\\', '/');
        String suffix = "/" + MANIFEST_FILE;
        if (!normalised.endsWith(suffix)) {
            return null;
        }
        String parent = normalised.substring(0, normalised.length() - suffix.length());
        int slash = parent.lastIndexOf('/');
        return slash < 0 ? parent : parent.substring(slash + 1);
    }
}
