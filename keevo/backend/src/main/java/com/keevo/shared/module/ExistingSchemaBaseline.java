package com.keevo.shared.module;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How a converted module adopts tenant schemas whose tables were created before it had migrations (Story 13.9).
 * When the module's history table does not exist yet and {@link #probeTable()} already exists in the tenant schema,
 * the module's Flyway <b>baselines at {@link #version()} without executing</b> the scripts up to it: the existing
 * tables are never touched (no column, default, index or constraint added, no row stamped). Later scripts
 * ({@code V<version+1>}…) run as usual. A schema without the probe table runs every script from {@code V1}.
 *
 * @param probeTable a table the module's {@code V<version>} creates (lower-case SQL identifier)
 * @param version    the version the existing tables correspond to (e.g. {@code "1"})
 */
public record ExistingSchemaBaseline(String probeTable, String version) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9]+(\\.[0-9]+)*$");

    public ExistingSchemaBaseline {
        Objects.requireNonNull(probeTable, "probeTable");
        Objects.requireNonNull(version, "version");
        if (!IDENTIFIER.matcher(probeTable).matches()) {
            throw new IllegalArgumentException("Invalid probe table name (length=" + probeTable.length() + ")");
        }
        if (!VERSION.matcher(version).matches() || version.matches("^0+(\\.0+)*$")) {
            throw new IllegalArgumentException("The baseline version must be a positive Flyway version");
        }
    }
}
