package com.keevo.shared.module;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 13.1 — manifest validation, one test per error row of the spec's I/O matrix plus the field rules. */
class ModuleManifestLoaderTest {

    private static final String CORE = """
            {"id":"core","version":"1.0.0","displayName":"Socle","category":"core","requires":[],"free":true}""";

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ModuleManifestLoader.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(ModuleManifestLoader.class)).detachAppender(logs);
    }

    private static String loc(String dir) {
        return "test:keevo/modules/" + dir + "/module.json";
    }

    private static ModuleManifestSource src(String dir, String json) {
        return new ModuleManifestSource(loc(dir), dir, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String module(String id, String requires) {
        return "{\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"displayName\":\"Module " + id
                + "\",\"category\":\"business\",\"requires\":" + requires + "}";
    }

    /** {@link #module} with extra top-level fields (a JSON fragment starting with a comma). */
    private static String module(String id, String requires, String extra) {
        String base = module(id, requires);
        return base.substring(0, base.length() - 1) + extra + "}";
    }

    private static List<ModuleManifestSource> withCore(ModuleManifestSource... others) {
        List<ModuleManifestSource> sources = new ArrayList<>();
        sources.add(src("core", CORE));
        sources.addAll(List.of(others));
        return sources;
    }

    private static List<String> problemsOf(List<ModuleManifestSource> sources) {
        try {
            ModuleManifestLoader.load(sources);
        } catch (InvalidModuleManifestException e) {
            return e.problems();
        }
        throw new AssertionError("expected an InvalidModuleManifestException");
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("valid set: parsed, sorted by id, defaults applied; stored JSON = validated manifest, unknown keys dropped + WARN")
    void validSet() {
        List<LoadedModuleManifest> loaded = ModuleManifestLoader.load(withCore(
                src("pos", """
                        {"id":"pos","version":"1.2.3","displayName":"Point de vente","category":"business",
                         "requires":["core","catalog"],"enhances":["crm"],"entitlement":"module.pos","free":true,
                         "extensionPoints":{"declares":["pos.checkout"],"fills":[],"slots":[]},"tables":["sales"]}"""),
                src("catalog", module("catalog", "[\"core\"]")),
                src("crm", module("crm", "[\"core\"]"))));

        assertThat(loaded).extracting(m -> m.manifest().id()).containsExactly("catalog", "core", "crm", "pos");
        ModuleManifest pos = loaded.get(3).manifest();
        assertThat(pos.version()).isEqualTo("1.2.3");
        assertThat(pos.category()).isEqualTo(ModuleCategory.BUSINESS);
        assertThat(pos.requires()).containsExactly("core", "catalog");
        assertThat(pos.enhances()).containsExactly("crm");
        assertThat(pos.extensionPoints().declares()).containsExactly("pos.checkout");
        assertThat(pos.free()).isTrue();
        assertThat(loaded.get(3).json()).doesNotContain("tables", "slots");
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains(loc("pos"), "unknown key 'tables'"))
                .anySatisfy(w -> assertThat(w).contains(loc("pos"), "unknown key 'extensionPoints.slots'"));

        ModuleManifest catalog = loaded.get(0).manifest();
        assertThat(catalog.entitlement()).isEqualTo("module.catalog");
        assertThat(catalog.free()).isFalse();
        assertThat(catalog.enhances()).isEmpty();
        assertThat(catalog.extensionPoints()).isEqualTo(ModuleManifest.ExtensionPoints.NONE);
        assertThat(loaded.get(0).json()).isEqualTo("{\"id\":\"catalog\",\"version\":\"1.0.0\",\"displayName\":"
                + "\"Module catalog\",\"category\":\"business\",\"requires\":[\"core\"],\"enhances\":[],\"publishes\":[],"
                + "\"consumes\":[],\"provides\":[],\"needs\":[],\"extensionPoints\":{\"declares\":[],\"fills\":[]},"
                + "\"permissions\":[],\"routes\":[],\"entitlement\":\"module.catalog\",\"free\":false}");
    }

    @Test
    @DisplayName("malformed JSON: fails naming the file")
    void malformedJson() {
        assertThat(problemsOf(withCore(src("pos", "{\"id\":\"pos\",")))).singleElement().asString()
                .startsWith(loc("pos") + ": malformed JSON");
        assertThat(problemsOf(withCore(src("pos", "[1,2]")))).singleElement().asString()
                .contains(loc("pos"), "must be a JSON object");
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]").replace("{\"id\":\"pos\",",
                "{\"id\":\"pos\",\"id\":\"pos\","))))).singleElement().asString()
                .contains(loc("pos"), "malformed JSON", "id");
    }

    @Test
    @DisplayName("unknown requires: fails naming the module and the unknown id")
    void unknownRequires() {
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\",\"ghost\"]"))))).singleElement().asString()
                .contains(loc("pos"), "'pos'", "unknown module 'ghost'");
    }

    @Test
    @DisplayName("cycles: fails listing every cycle (one per strongly connected component)")
    void cycles() {
        assertThat(problemsOf(withCore(src("aa", module("aa", "[\"core\",\"bb\"]")),
                src("bb", module("bb", "[\"core\",\"aa\"]"))))).singleElement().asString()
                .contains("cycle", "aa -> bb -> aa");
        assertThat(problemsOf(withCore(src("aa", module("aa", "[\"core\",\"aa\"]"))))).singleElement().asString()
                .contains("aa -> aa");
        assertThat(problemsOf(withCore(
                src("aa", module("aa", "[\"core\",\"bb\"]")), src("bb", module("bb", "[\"core\",\"aa\"]")),
                src("xx", module("xx", "[\"core\",\"yy\"]")), src("yy", module("yy", "[\"core\",\"zz\"]")),
                src("zz", module("zz", "[\"core\",\"xx\"]")))))
                .containsExactly("cycle in the 'requires' graph: aa -> bb -> aa",
                        "cycle in the 'requires' graph: xx -> yy -> zz -> xx");
    }

    @Test
    @DisplayName("unknown enhances: loads, WARN logged")
    void unknownEnhances() {
        List<LoadedModuleManifest> loaded = ModuleManifestLoader.load(withCore(src("pos",
                module("pos", "[\"core\"]", ",\"enhances\":[\"invoicing\"]"))));
        assertThat(loaded).hasSize(2);
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("pos", "invoicing"));
    }

    @Test
    @DisplayName("directory / id mismatch: fails naming both")
    void directoryMismatch() {
        assertThat(problemsOf(withCore(src("pos", module("sale", "[\"core\"]"))))).singleElement().asString()
                .contains(loc("pos"), "'sale'", "keevo/modules/pos/");
    }

    @Test
    @DisplayName("graph checks run on the valid manifests even when another file is invalid")
    void graphCheckedDespiteInvalidFile() {
        List<String> problems = problemsOf(withCore(src("crm", "not json"),
                src("pos", module("pos", "[\"core\",\"ghost\",\"crm\"]")),
                src("aa", module("aa", "[\"core\",\"aa\"]"))));
        assertThat(problems).hasSize(4)
                .anySatisfy(p -> assertThat(p).startsWith(loc("crm") + ": malformed JSON"))
                .anySatisfy(p -> assertThat(p).contains(loc("pos"), "unknown module 'ghost'"))
                .anySatisfy(p -> assertThat(p).contains(loc("pos"), "requires 'crm', whose manifest is invalid"))
                .anySatisfy(p -> assertThat(p).contains("aa -> aa"));
    }

    @Test
    @DisplayName("required fields missing, blank or not strings: one problem per field")
    void requiredFields() {
        List<String> problems = problemsOf(withCore(src("pos",
                "{\"id\":\"pos\",\"version\":\"\",\"displayName\":12,\"requires\":[\"core\"]}")));
        assertThat(problems).hasSize(3);
        assertThat(problems).allSatisfy(p -> assertThat(p).startsWith(loc("pos") + ":"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("'version'", "blank"))
                .anySatisfy(p -> assertThat(p).contains("'displayName'", "must be a string"))
                .anySatisfy(p -> assertThat(p).contains("'category'", "missing"));
        assertThat(problemsOf(List.of(src("core", "{\"version\":\"1.0.0\",\"displayName\":\"x\",\"category\":\"core\"}"))))
                .anySatisfy(p -> assertThat(p).contains(loc("core"), "'id'", "missing"));
    }

    @Test
    @DisplayName("id syntax (single hyphens, <= 50), semver, category, display name length")
    void fieldFormats() {
        assertThat(ModuleManifestLoader.load(withCore(src("p", module("p", "[\"core\"]")),
                src("import-tracking", module("import-tracking", "[\"core\"]"))))).hasSize(3);
        for (String bad : List.of("Pos", "pos-", "po--s", "-pos", "1pos", "pos_x", "a" + "b".repeat(50))) {
            assertThat(problemsOf(withCore(src(bad, module(bad, "[\"core\"]")))))
                    .as(bad).singleElement().asString().contains("'id'", "must match");
        }
        for (String bad : List.of("1.0", "1.0.0-beta", "v1.0.0", "01.0.0", "1.0.0.0")) {
            assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]").replace("1.0.0", bad)))))
                    .as(bad).singleElement().asString().contains("'version'", "semver");
        }
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]").replace("business", "addon")))))
                .singleElement().asString().contains("'category'", "addon", "core|business|premium|platform");
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]").replace("Module pos", "x".repeat(101))))))
                .singleElement().asString().contains("'displayName'", "100");
    }

    @Test
    @DisplayName("requires / enhances / lists must be arrays of strings; free a boolean; extensionPoints an object")
    void typedFields() {
        assertThat(problemsOf(withCore(src("pos", module("pos", "\"core\"")))))
                .anySatisfy(p -> assertThat(p).contains("'requires'", "JSON array of strings"));
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\", 3]")))))
                .singleElement().asString().contains("'requires'[1]", "non-blank string");
        String req = "[\"core\"]";
        assertThat(problemsOf(withCore(src("pos", module("pos", req, ",\"enhances\":{}")))))
                .singleElement().asString().contains("'enhances'", "JSON array");
        assertThat(problemsOf(withCore(src("pos", module("pos", req, ",\"routes\":[\"\"]")))))
                .singleElement().asString().contains("'routes'[0]");
        assertThat(problemsOf(withCore(src("pos", module("pos", req, ",\"free\":\"yes\"")))))
                .singleElement().asString().contains("'free'", "boolean");
        assertThat(problemsOf(withCore(src("pos", module("pos", req, ",\"extensionPoints\":[]")))))
                .singleElement().asString().contains("'extensionPoints'");
        assertThat(problemsOf(withCore(src("pos", module("pos", req, ",\"extensionPoints\":{\"fills\":\"x\"}")))))
                .singleElement().asString().contains("'extensionPoints.fills'");
    }

    @Test
    @DisplayName("lists: no duplicates; requires/enhances entries are module ids; no self-enhance; not in both lists")
    void listRules() {
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\",\"core\"]")))))
                .singleElement().asString().contains(loc("pos"), "'requires'", "'core' more than once");
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]", ",\"permissions\":[\"A\",\"B\",\"A\"]")))))
                .singleElement().asString().contains("'permissions'", "'A' more than once");
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]", ",\"enhances\":[\"Bad_Id\"]")))))
                .singleElement().asString().contains("'enhances'", "'Bad_Id'", "not a valid module id");
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\",\"Ghost\"]")))))
                .singleElement().asString().contains("'requires'", "'Ghost'", "not a valid module id");
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]", ",\"enhances\":[\"pos\"]")))))
                .singleElement().asString().contains("'pos' cannot enhance itself");
        assertThat(problemsOf(withCore(src("crm", module("crm", "[\"core\"]")),
                src("pos", module("pos", "[\"core\",\"crm\"]", ",\"enhances\":[\"crm\"]")))))
                .singleElement().asString().contains(loc("pos"), "'requires' and 'enhances' both list 'crm'");
    }

    @Test
    @DisplayName("entitlement: module.<id> syntax, unique across manifests")
    void entitlementRules() {
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]", ",\"entitlement\":\" \"")))))
                .singleElement().asString().contains("'entitlement'");
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]", ",\"entitlement\":\"pos\"")))))
                .singleElement().asString().contains("'entitlement'", "'pos'", "must match");
        assertThat(problemsOf(withCore(src("crm", module("crm", "[\"core\"]")),
                src("pos", module("pos", "[\"core\"]", ",\"entitlement\":\"module.crm\"")))))
                .singleElement().asString().contains("duplicate entitlement 'module.crm'", loc("crm"), loc("pos"));
    }

    @Test
    @DisplayName("core rules: exactly one core, category core, requiring nothing; every other module requires core")
    void coreRules() {
        assertThat(problemsOf(List.of(src("pos", module("pos", "[]")))))
                .anySatisfy(p -> assertThat(p).contains("no valid module with id 'core'"));
        assertThat(problemsOf(withCore(src("pos", module("pos", "[]")))))
                .singleElement().asString().contains("'pos'", "must require 'core'");
        assertThat(problemsOf(List.of(src("core", CORE.replace("\"requires\":[]", "\"requires\":[\"pos\"]")),
                src("pos", module("pos", "[\"core\"]")))))
                .anySatisfy(p -> assertThat(p).contains("module 'core' must not require"));
        assertThat(problemsOf(List.of(src("core", CORE.replace("\"category\":\"core\"", "\"category\":\"business\"")))))
                .anySatisfy(p -> assertThat(p).contains(loc("core"), "module 'core' must have category 'core'"));
        assertThat(problemsOf(List.of())).singleElement().asString().contains("no module manifest found");
    }

    @Test
    @DisplayName("duplicate ids: fails naming both files")
    void duplicateIds() {
        List<ModuleManifestSource> sources = withCore(
                new ModuleManifestSource("jar-a:keevo/modules/pos/module.json", "pos",
                        module("pos", "[\"core\"]").getBytes(StandardCharsets.UTF_8)),
                new ModuleManifestSource("jar-b:keevo/modules/pos/module.json", "pos",
                        module("pos", "[\"core\"]").getBytes(StandardCharsets.UTF_8)));
        assertThat(problemsOf(sources)).singleElement().asString()
                .contains("duplicate module id 'pos'", "jar-a:keevo/modules/pos/module.json",
                        "jar-b:keevo/modules/pos/module.json");
    }

    @Test
    @DisplayName("13.6 — 'needs' entries: capability name ending in Port, optional '?' suffix")
    void needsFormat() {
        assertThat(ModuleManifestLoader.load(withCore(
                src("crm", module("crm", "[\"core\"]", ",\"provides\":[\"ClientLookupPort\"]")),
                src("pos", module("pos", "[\"core\",\"crm\"]",
                        ",\"needs\":[\"ClientLookupPort\",\"InvoiceLookupPort?\"]"))))).hasSize(3);
        for (String bad : List.of("clientLookupPort", "ClientLookup", "Client-LookupPort", "ClientLookupPort??",
                "?ClientLookupPort", "Port")) {
            assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]",
                    ",\"needs\":[\"" + bad + "\"]")))))
                    .as(bad).anySatisfy(p -> assertThat(p).contains(loc("pos"), "'needs' entry '" + bad + "'"));
        }
    }

    @Test
    @DisplayName("13.6 — a 'provides' name is unique across manifests")
    void providesUnique() {
        assertThat(problemsOf(withCore(
                src("crm", module("crm", "[\"core\"]", ",\"provides\":[\"ClientLookupPort\"]")),
                src("pos", module("pos", "[\"core\"]", ",\"provides\":[\"ClientLookupPort\"]")))))
                .singleElement().asString()
                .contains("capability 'ClientLookupPort' provided by two modules: 'crm'", "'pos'", loc("pos"));
    }

    @Test
    @DisplayName("13.6 — a hard need is provided within the 'requires' closure (transitively); an optional one may be absent")
    void hardNeeds() {
        // transitive: pos requires sales, sales requires crm, crm provides
        assertThat(ModuleManifestLoader.load(withCore(
                src("crm", module("crm", "[\"core\"]", ",\"provides\":[\"ClientLookupPort\"]")),
                src("sales", module("sales", "[\"core\",\"crm\"]")),
                src("pos", module("pos", "[\"core\",\"sales\"]", ",\"needs\":[\"ClientLookupPort\"]"))))).hasSize(4);
        // provided, but outside the closure
        assertThat(problemsOf(withCore(
                src("crm", module("crm", "[\"core\"]", ",\"provides\":[\"ClientLookupPort\"]")),
                src("pos", module("pos", "[\"core\"]", ",\"needs\":[\"ClientLookupPort\"]")))))
                .singleElement().asString()
                .contains(loc("pos"), "module 'pos' needs 'ClientLookupPort'", "provided by 'crm'");
        // provided by nobody
        assertThat(problemsOf(withCore(
                src("pos", module("pos", "[\"core\"]", ",\"needs\":[\"XPort\"]")))))
                .singleElement().asString().contains("needs 'XPort'", "no installed module provides it");
        // a module's own capability does not satisfy its hard need
        assertThat(problemsOf(withCore(src("pos", module("pos", "[\"core\"]",
                ",\"provides\":[\"XPort\"],\"needs\":[\"XPort\"]")))))
                .singleElement().asString().contains("needs 'XPort'");
        // optional need absent: OK, WARN only
        assertThat(ModuleManifestLoader.load(withCore(
                src("pos", module("pos", "[\"core\"]", ",\"needs\":[\"XPort?\"]"))))).hasSize(2);
        assertThat(warnings()).anySatisfy(w -> assertThat(w).contains("module 'pos' optionally needs 'XPort'",
                "no installed module provides"));
    }

    @Test
    @DisplayName("13.6 — no optional-need WARN when someone provides it")
    void optionalNeedProvided() {
        ModuleManifestLoader.load(withCore(
                src("crm", module("crm", "[\"core\"]", ",\"provides\":[\"ClientLookupPort\"]")),
                src("pos", module("pos", "[\"core\"]", ",\"needs\":[\"ClientLookupPort?\"]"))));
        assertThat(warnings()).noneSatisfy(w -> assertThat(w).contains("optionally needs"));
    }

    @Test
    @DisplayName("13.6 — 'provides' entries: capability name ending in Port")
    void providesFormat() {
        for (String bad : List.of("clientLookupPort", "ClientLookup", "ClientLookupPort?", "Client-LookupPort", "Port")) {
            assertThat(problemsOf(withCore(src("crm", module("crm", "[\"core\"]",
                    ",\"provides\":[\"" + bad + "\"]")))))
                    .as(bad).anySatisfy(p -> assertThat(p).contains(loc("crm"), "'provides' entry '" + bad + "'"));
        }
    }

    @Test
    @DisplayName("13.6 — the same name cannot be both a hard and an optional need")
    void hardAndOptionalNeed() {
        assertThat(problemsOf(withCore(
                src("crm", module("crm", "[\"core\"]", ",\"provides\":[\"ClientLookupPort\"]")),
                src("pos", module("pos", "[\"core\",\"crm\"]",
                        ",\"needs\":[\"ClientLookupPort?\",\"ClientLookupPort\"]")))))
                .singleElement().asString()
                .contains(loc("pos"), "lists 'ClientLookupPort' both as a hard and an optional need");
    }

    @Test
    @DisplayName("every problem of the set is reported at once, in the exception message")
    void allProblemsInOneMessage() {
        assertThatThrownBy(() -> ModuleManifestLoader.load(withCore(src("pos", module("sale", "[\"core\"]")),
                src("crm", "not json"))))
                .isInstanceOf(InvalidModuleManifestException.class)
                .hasMessageContaining("2 problem(s)")
                .hasMessageContaining(loc("pos"))
                .hasMessageContaining(loc("crm"));
    }

    @Test
    void cycleFinders() {
        assertThat(ModuleManifestLoader.findRequiresCycle(Map.of(
                "pos", List.of("catalog", "inventory"), "inventory", List.of("catalog"),
                "catalog", List.of("core")))).isEmpty();
        assertThat(ModuleManifestLoader.findRequiresCycle(Map.of("a", List.of("b"), "b", List.of("c"), "c", List.of("a"))))
                .hasValueSatisfying(cycle -> assertThat(cycle).contains("a", "b", "c"));
        assertThat(ModuleManifestLoader.findRequiresCycles(Map.of(
                "a", List.of("b"), "b", List.of("a", "c"), "c", List.of("c"), "d", List.of("a"))))
                .hasSize(2)
                .anySatisfy(c -> assertThat(c).containsExactly("a", "b", "a"))
                .anySatisfy(c -> assertThat(c).containsExactly("c", "c"));
        assertThat(ModuleManifestLoader.findRequiresCycles(Map.of("a", List.of("b"), "b", List.of()))).isEmpty();
    }

    @Test
    void directoryOfLocation() {
        assertThat(ModuleManifestLoader.directoryOf(
                "jar:nested:/app.jar/!BOOT-INF/classes/!/keevo/modules/pos/module.json")).isEqualTo("pos");
        assertThat(ModuleManifestLoader.directoryOf("file:/x/target/classes/keevo/modules/core/module.json"))
                .isEqualTo("core");
        assertThat(ModuleManifestLoader.directoryOf("file:/x/other.json")).isNull();
    }
}
