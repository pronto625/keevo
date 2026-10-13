package com.keevo.shared.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 11.4 AC7 — build-time guard: every Java site that interpolates a tenant schema name into
 * SQL must validate that same value with {@code TenantSchema.validate} / {@code TenantSchema.isValid}
 * in code (not a comment) on the same line or within {@value #WINDOW} lines above.
 *
 * <p>Source scan of {@code src/main/java}. Interpolation shapes detected (the interpolated expression
 * is extracted and must appear in the guarding call's line):
 * <ul>
 *   <li>{@code + expr + ".table"} / {@code + expr + "\".table"} — schema-qualified identifier;</li>
 *   <li>{@code schemaExpr + "." + table} — dotted concatenation of a schema-named expression;</li>
 *   <li>{@code "...\"" + expr + "\"..."} — quoted identifier ({@code search_path}, {@code CREATE SCHEMA});</li>
 *   <li>{@code .append(schemaExpr)} — StringBuilder with a schema-named expression;</li>
 *   <li>{@code String.format(X_SQL|X_TPL, …)}, or an inline {@code %s} SQL template /
 *       {@code .formatted(…)} — at least one argument must be guarded.</li>
 * </ul>
 * Reviewed exceptions live in {@code tenant-schema-interpolation-allowlist.txt} (test resources);
 * an entry that matches no line fails the test (stale entry).
 *
 * <p>Heuristic, not a proof: a guard on a same-named variable of another method within the window
 * still counts, so reviewers keep an eye on new SQL.
 */
@DisplayName("TenantSchema — interpolation guard (Story 11.4 AC7)")
class TenantSchemaInterpolationGuardTest {

    static final int WINDOW = 20;

    private static final String ALLOWLIST = "/tenant-schema-interpolation-allowlist.txt";
    private static final String EXPR = "([\\w.()]+)";

    private static final List<Pattern> CONCATENATIONS = List.of(
            // + schema + ".table"   |   + schema + "\".table"
            Pattern.compile("(?:^|[+(,=])\\s*" + EXPR + "\\s*\\+\\s*\"(?:\\\\\")?\\.\\w"),
            // schema + "." + table   |   schema + "\"." + table
            Pattern.compile("([\\w.()]*[sS]chema[\\w.()]*)\\s*\\+\\s*\"(?:\\\\\")?\\.\"\\s*\\+"),
            // "...\"" + schema + "\"..."
            Pattern.compile("\\\\\"\"\\s*\\+\\s*" + EXPR + "\\s*\\+\\s*\"\\\\\""),
            // sb.append(schema)
            Pattern.compile("\\.append\\(\\s*([\\w.()]*[sS]chema[\\w.()]*)\\s*\\)"));

    /** String.format(SOME_SQL, …) / String.format(SQL_X_TPL, …). */
    private static final Pattern FORMAT_SQL_CONSTANT = Pattern.compile("String\\.format\\(\\s*[A-Z0-9_]*(SQL|TPL)");
    private static final Pattern FORMAT_CALL = Pattern.compile("String\\.format\\(|\\.formatted\\(");
    private static final Pattern SQL_KEYWORD = Pattern.compile(
            "(?i)\\b(SELECT|INSERT|UPDATE|DELETE|FROM|JOIN|ALTER|CREATE|DROP|TABLE|INDEX|search_path)\\b");

    private static final Pattern GUARD_CALL = Pattern.compile("TenantSchema\\.(validate|isValid)\\(");

    @Test
    @DisplayName("every schema interpolation into SQL is validated by TenantSchema nearby")
    void everyInterpolationIsGuarded() throws IOException {
        Path mainSources = mainSources();
        List<String[]> allowlist = loadAllowlist();
        Set<Integer> usedEntries = new HashSet<>();
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.walk(mainSources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                scanned++;
                String rel = mainSources.relativize(file).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    if (isComment(trimmed)) {
                        continue;
                    }
                    List<String> exprs = interpolatedExpressions(lines, i);
                    if (exprs.isEmpty() || isGuarded(lines, i, exprs)) {
                        continue;
                    }
                    int entry = allowlistEntry(allowlist, rel, trimmed);
                    if (entry >= 0) {
                        usedEntries.add(entry);
                        continue;
                    }
                    violations.add(rel + ":" + (i + 1) + "  " + trimmed);
                }
            }
        }

        assertThat(scanned).as("Java files scanned under " + mainSources).isGreaterThan(100);
        assertThat(violations)
                .as("Unvalidated tenant schema interpolation — call TenantSchema.validate/isValid on the "
                        + "interpolated value right before building the SQL, or add a reviewed entry to "
                        + ALLOWLIST)
                .isEmpty();
        List<String> stale = new ArrayList<>();
        for (int k = 0; k < allowlist.size(); k++) {
            if (!usedEntries.contains(k)) stale.add(allowlist.get(k)[0] + " :: " + allowlist.get(k)[1]);
        }
        assertThat(stale).as("Stale entries in " + ALLOWLIST + " (match no flagged line) — remove them").isEmpty();
    }

    @Test
    @DisplayName("the scan detects interpolations and only accepts a real guard on the same value (self-check)")
    void detectorSelfCheck() {
        assertThat(exprs("jdbc.queryForObject(\"SELECT 1 FROM \" + schema + \".sales\", Long.class);"))
                .containsExactly("schema");
        assertThat(exprs("        + schema + \"\\\".employees WHERE user_id = ?\", id);")).containsExactly("schema");
        assertThat(exprs("stmt.execute(\"SET search_path TO \\\"\" + schema + \"\\\"\");")).contains("schema");
        assertThat(exprs("String t = schema + \".\" + table;")).containsExactly("schema");
        assertThat(exprs("sql.append(tenant.schemaName()).append(\".products\");")).contains("tenant.schemaName()");
        assertThat(exprs("jdbc.update(String.format(UPDATE_PLAN_SQL, schema), id);")).containsExactly("schema");
        assertThat(interpolatedExpressions(List.of("String sql = String.format(",
                "        \"ALTER TABLE \\\"%s\\\".\\\"%s\\\" ADD COLUMN %s\",", "        schema, table, def);"), 0))
                .containsExactly("schema", "table", "def");
        assertThat(exprs("String msg = String.format(\"%d items in %s\", n, label);")).isEmpty();
        assertThat(exprs("log.info(\"Tenant {} skipped\", schema);")).isEmpty();
        assertThat(exprs("throw new X(\"failed for \" + schemaName + \": \" + e);")).isEmpty();

        String sql = "jdbc.queryForObject(\"SELECT 1 FROM \" + schema + \".sales\", Long.class);";
        assertThat(guarded("String schema = tenant.schemaName();", sql)).isFalse();
        assertThat(guarded("String schema = TenantSchema.validate(tenant.schemaName());", sql)).isTrue();
        assertThat(guarded("if (!TenantSchema.isValid(schema)) continue;", sql)).isTrue();
        // a comment is not a guard
        assertThat(guarded("// schema validated with TenantSchema.validate above", sql)).isFalse();
        assertThat(guarded("String schema = load(); // TenantSchema.validate(schema) in caller", sql)).isFalse();
        // a guard on another variable is not a guard
        assertThat(guarded("if (!TenantSchema.isValid(schemaName)) return;", sql)).isFalse();
        // inline guard
        assertThat(guarded("int x = 0;",
                "jdbc.query(\"SELECT 1 FROM \" + TenantSchema.validate(schema) + \".sales\");")).isTrue();
    }

    private static List<String> exprs(String line) {
        return interpolatedExpressions(List.of(line), 0);
    }

    private static boolean guarded(String guardLine, String sqlLine) {
        List<String> lines = List.of(guardLine, sqlLine);
        return isGuarded(lines, 1, interpolatedExpressions(lines, 1));
    }

    /** Interpolated expressions on line {@code i}; empty when the line builds no schema-qualified SQL. */
    static List<String> interpolatedExpressions(List<String> lines, int i) {
        String line = stripTrailingComment(lines.get(i));
        List<String> out = new ArrayList<>();
        for (Pattern p : CONCATENATIONS) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                if (!out.contains(m.group(1))) out.add(m.group(1));
            }
        }
        Matcher call = FORMAT_CALL.matcher(line);
        if (call.find()) {
            String context = String.join("\n", lines.subList(i, Math.min(lines.size(), i + 4)));
            int start = context.indexOf(call.group()) + call.group().length();
            boolean formatted = call.group().startsWith(".formatted");
            boolean sql;
            if (formatted) {
                String above = String.join("\n", lines.subList(Math.max(0, i - 10), i + 1));
                sql = above.contains("%s") && SQL_KEYWORD.matcher(above).find();
            } else {
                sql = FORMAT_SQL_CONSTANT.matcher(line).find()
                        || (context.contains("%s") && SQL_KEYWORD.matcher(context).find());
            }
            if (sql) {
                List<String> args = topLevelArguments(context, start);
                for (String arg : formatted ? args : args.subList(Math.min(1, args.size()), args.size())) {
                    if (!out.contains(arg)) out.add(arg);
                }
            }
        }
        return out;
    }

    /** Comma-separated top-level arguments of the call whose '(' ends just before {@code from}. */
    private static List<String> topLevelArguments(String text, int from) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int k = from; k < text.length(); k++) {
            char c = text.charAt(k);
            if (inString) {
                current.append(c);
                if (c == '\\' && k + 1 < text.length()) {
                    current.append(text.charAt(++k));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(current.toString().trim());
                    return args;
                }
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        args.add(current.toString().trim());
        return args;
    }

    /**
     * Every expression (for a format call: at least one argument) is passed to
     * {@code TenantSchema.validate/isValid} — or appears on a code line holding such a call — on
     * this line or within {@value #WINDOW} non-comment lines above. Comments never count.
     */
    static boolean isGuarded(List<String> lines, int index, List<String> exprs) {
        boolean formatCall = FORMAT_CALL.matcher(stripTrailingComment(lines.get(index))).find()
                && CONCATENATIONS.stream().noneMatch(p -> p.matcher(lines.get(index)).find());
        List<String> codeWindow = new ArrayList<>();
        for (int j = index; j >= Math.max(0, index - WINDOW); j--) {
            String trimmed = lines.get(j).trim();
            if (isComment(trimmed)) continue;
            String code = stripTrailingComment(lines.get(j));
            if (GUARD_CALL.matcher(code).find()) codeWindow.add(code);
        }
        if (formatCall) {
            return exprs.stream().anyMatch(e -> mentionedIn(codeWindow, e));
        }
        return exprs.stream().allMatch(e -> mentionedIn(codeWindow, e));
    }

    private static boolean mentionedIn(List<String> guardLines, String expr) {
        Pattern token = Pattern.compile("(?<![\\w.])" + Pattern.quote(expr) + "(?![\\w(])");
        return guardLines.stream().anyMatch(l -> token.matcher(l).find());
    }

    private static boolean isComment(String trimmed) {
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    /** Removes a trailing {@code // comment} that is outside string literals. */
    static String stripTrailingComment(String line) {
        boolean inString = false;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (inString) {
                if (c == '\\') k++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '/' && k + 1 < line.length() && line.charAt(k + 1) == '/') {
                return line.substring(0, k);
            }
        }
        return line;
    }

    private static int allowlistEntry(List<String[]> allowlist, String rel, String trimmed) {
        for (int k = 0; k < allowlist.size(); k++) {
            if (allowlist.get(k)[0].equals(rel) && trimmed.contains(allowlist.get(k)[1])) return k;
        }
        return -1;
    }

    /** {@code src/main/java} from the module dir, or from the repository root. */
    private static Path mainSources() {
        Path base = Path.of(System.getProperty("user.dir"));
        for (Path candidate : List.of(base.resolve("src/main/java"), base.resolve("keevo/backend/src/main/java"))) {
            if (Files.isDirectory(candidate.resolve("com/keevo"))) return candidate;
        }
        throw new IllegalStateException("src/main/java not found from " + base);
    }

    /** Entries: {@code path/From/Main/Java.java :: exact substring of the line   # reason}. */
    private static List<String[]> loadAllowlist() throws IOException {
        try (InputStream in = TenantSchemaInterpolationGuardTest.class.getResourceAsStream(ALLOWLIST)) {
            assertThat(in).as(ALLOWLIST + " on the test classpath").isNotNull();
            List<String[]> entries = new ArrayList<>();
            for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int hash = line.indexOf("   #");
                if (hash >= 0) {
                    line = line.substring(0, hash).strip();
                }
                String[] parts = line.split("\\s::\\s", 2);
                assertThat(parts).as("allow-list entry '" + raw + "' must be 'path :: substring'").hasSize(2);
                entries.add(new String[] {parts[0].strip(), parts[1].strip()});
            }
            return entries;
        }
    }
}
