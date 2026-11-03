package com.keevo.shared.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story 16.12 — the full structural shape of one tenant schema, as sorted text lines per kind: columns (from
 * {@code information_schema.columns}: position, type, length/precision, nullability, default), indexes
 * ({@code pg_indexes}), constraints ({@code pg_constraint} definitions), triggers, functions and table ACLs. Schema
 * qualifiers and the owner role are normalised so two schemas (or two databases) compare as text. Flyway history
 * tables ({@code flyway_history_*}) are ignored: they record how the schema was built, not its shape.
 *
 * <p>The committed reference {@value #RESOURCE} is the shape of a fresh tenant built by the programmatic provisioner
 * before 16.12 (captured by {@link TenantShapePostgresIntegrationTest} with {@code -Dkeevo.tenant-shape.capture=true}).
 */
public final class TenantSchemaShape {

    /** Classpath location of the reference shape. */
    public static final String RESOURCE = "tenant-shape/fresh-tenant-shape.json";
    /** Same file in the source tree (capture mode writes it there). */
    public static final Path SOURCE_FILE = Path.of("src/test/resources", RESOURCE);

    private static final String HISTORY = "flyway\\_history\\_%";

    private static final String COLUMNS = """
            SELECT table_name || '.' || column_name || ' #' || ordinal_position || ' ' || data_type
                   || ' udt=' || udt_name
                   || COALESCE(' len=' || character_maximum_length, '')
                   || COALESCE(' prec=' || numeric_precision || ',' || numeric_scale, '')
                   || COALESCE(' dtprec=' || datetime_precision, '')
                   || ' nullable=' || is_nullable
                   || COALESCE(' default=' || column_default, '')
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name NOT LIKE ?""";

    private static final String INDEXES = """
            SELECT tablename || ': ' || indexdef FROM pg_indexes
            WHERE schemaname = ? AND tablename NOT LIKE ?""";

    private static final String CONSTRAINTS = """
            SELECT cl.relname || '.' || co.conname || ' [' || co.contype::text || '] ' || pg_get_constraintdef(co.oid)
                   || CASE WHEN co.convalidated THEN '' ELSE ' (NOT VALID)' END
            FROM pg_constraint co
            JOIN pg_class cl ON cl.oid = co.conrelid
            JOIN pg_namespace n ON n.oid = cl.relnamespace
            WHERE n.nspname = ? AND cl.relname NOT LIKE ?""";

    private static final String TRIGGERS = """
            SELECT cl.relname || ': ' || pg_get_triggerdef(t.oid)
            FROM pg_trigger t
            JOIN pg_class cl ON cl.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = cl.relnamespace
            WHERE n.nspname = ? AND NOT t.tgisinternal AND cl.relname NOT LIKE ?""";

    private static final String FUNCTIONS = """
            SELECT pg_get_functiondef(p.oid)
            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = ? AND p.proname NOT LIKE ?""";

    private static final String TABLE_ACLS = """
            SELECT cl.relname || ': ' || COALESCE(cl.relacl::text, '(default)')
            FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace
            WHERE n.nspname = ? AND cl.relkind = 'r' AND cl.relname NOT LIKE ?""";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private TenantSchemaShape() {
    }

    /** The shape of {@code schema}, read on {@code conn} (the connection's settings are not changed). */
    public static Map<String, List<String>> capture(Connection conn, String schema) throws SQLException {
        String owner = currentUser(conn);
        Map<String, List<String>> shape = new LinkedHashMap<>();
        shape.put("columns", lines(conn, COLUMNS, schema, owner));
        shape.put("indexes", lines(conn, INDEXES, schema, owner));
        shape.put("constraints", lines(conn, CONSTRAINTS, schema, owner));
        shape.put("triggers", lines(conn, TRIGGERS, schema, owner));
        shape.put("functions", lines(conn, FUNCTIONS, schema, owner));
        shape.put("tableAcls", lines(conn, TABLE_ACLS, schema, owner));
        return shape;
    }

    /** Tables of the shape (from its column lines). */
    public static List<String> tables(Map<String, List<String>> shape) {
        return shape.get("columns").stream().map(l -> l.substring(0, l.indexOf('.'))).distinct().sorted().toList();
    }

    /** The committed reference shape. */
    public static Map<String, List<String>> reference() {
        try (InputStream in = TenantSchemaShape.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing reference shape " + RESOURCE);
            }
            return JSON.readValue(in, new TypeReference<LinkedHashMap<String, List<String>>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code shape} as the reference (capture mode). */
    public static void write(Map<String, List<String>> shape) {
        try {
            Files.createDirectories(SOURCE_FILE.getParent());
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                    .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
            Files.writeString(SOURCE_FILE, JSON.writer(printer).writeValueAsString(shape) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(Connection conn, String sql, String schema, String owner) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, HISTORY);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(normalise(rs.getString(1), schema, owner));
                }
            }
        }
        out.sort(null);
        return out;
    }

    private static String normalise(String line, String schema, String owner) {
        return line.replace("\"" + schema + "\".", "")
                .replace(schema + ".", "")
                .replaceAll("\\s+", " ")
                .replace("{" + owner + "=", "{OWNER=")
                .replace("," + owner + "=", ",OWNER=")
                .replace("/" + owner, "/OWNER")
                .trim();
    }

    private static String currentUser(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT current_user");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
