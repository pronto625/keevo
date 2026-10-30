package com.keevo.shared.application.port.export;

import java.util.List;
import java.util.Set;

/**
 * ExportDatasetProvider — port through which a domain contributes one exportable dataset to the
 * export module (Story v1s-18-1, FR55/FR94/FR96). Mirrors the sync {@code DeltaEntityProvider}.
 *
 * <p>Lives in the OPEN {@code shared} module so that catalog, commerce, store, inventory… can
 * implement it without depending on {@code reporting} (which already depends on them — a port
 * owned by reporting would create Modulith cycles). A new domain adds a provider bean; the export
 * module discovers it by {@link #key()} without being changed.
 *
 * <p><b>Contract</b>
 * <ul>
 *   <li><b>Tenant scoping</b> comes from the tenant-routed JPA {@code EntityManager}
 *       ({@code search_path} set from {@code TenantContext}). Implementations must never read
 *       through {@code JdbcTemplate} or a raw {@code DataSource}.</li>
 *   <li><b>Bounded memory</b>: {@link #readPage} returns at most {@code limit} rows, read by
 *       keyset in a stable order, each call in its own read-only transaction.</li>
 *   <li><b>Row values</b> match {@link #columns()} order and types: {@code String},
 *       {@code Long}/{@code Integer}, {@code BigDecimal}, {@code Boolean}, {@code LocalDate},
 *       {@code Instant}; {@code null} is allowed. Amounts are integer XAF.</li>
 * </ul>
 */
public interface ExportDatasetProvider {

    /** Stable dataset key used in requests ({@code [a-z][a-z0-9_]*}, e.g. {@code stores}). */
    String key();

    /**
     * Story 13.10a review — the module owning the data ({@code module.json} id, e.g. {@code crm}, {@code counting}):
     * the export is refused ({@code MODULE_NOT_ENTITLED}) when it is disabled for the tenant, whatever the format.
     */
    String moduleId();

    /** French label (sheet name, audit label). */
    String label();

    /** Column schema, in row order. */
    List<ExportColumn> columns();

    /**
     * Internal values appended to each row after the {@link #columns()} values, in this order
     * (Story v1s-16-20): ids a PDF model needs to group or look rows up. They are never written to
     * a file — the CSV / XLSX renderers only write {@link #columns()} — and are not part of the
     * export contract.
     */
    default List<String> internalKeys() {
        return List.of();
    }

    /** Number of rows matching {@code filter} (decides direct vs background export). */
    long count(ExportFilter filter);

    /**
     * Read one page.
     *
     * @param filter filters
     * @param cursor opaque cursor from a previous page's {@link ExportPage#nextCursor()},
     *               {@code null} for the first page
     * @param limit  maximum number of rows to return
     * @return the page; {@code nextCursor == null} when there is no further row
     */
    ExportPage readPage(ExportFilter filter, String cursor, int limit);

    /**
     * Whether {@link ExportFilter#storeId()} narrows this dataset (Story v1s-18-2). Event datasets
     * filter on their store column; reference datasets only when they are store-scoped.
     */
    default boolean supportsStoreFilter() {
        return true;
    }

    /**
     * Whether {@link ExportFilter#from()} / {@link ExportFilter#to()} narrow this dataset (Story
     * v1s-18-2). Event datasets filter on their event column; reference datasets (current
     * snapshot: products, clients…) ignore the period.
     */
    default boolean supportsPeriodFilter() {
        return true;
    }

    /**
     * Dataset-specific criteria keys accepted in {@link ExportFilter#criteria()} (Story v1s-18-2).
     * A request carrying any other key for this dataset is rejected (400).
     */
    default Set<String> criteriaKeys() {
        return Set.of();
    }
}
