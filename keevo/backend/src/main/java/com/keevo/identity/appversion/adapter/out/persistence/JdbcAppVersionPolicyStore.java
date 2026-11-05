package com.keevo.identity.appversion.adapter.out.persistence;

import com.keevo.identity.appversion.domain.model.AppPlatform;
import com.keevo.identity.appversion.domain.model.AppVersionPolicy;
import com.keevo.identity.appversion.domain.port.out.AppVersionPolicyStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.10 — JDBC adapter of {@link AppVersionPolicyStore} on {@code public.app_versions} (public schema only, no
 * tenant schema). Runs on the caller's transaction; no cache: the check is one primary-key read.
 */
@Component
@DependsOnDatabaseInitialization
public class JdbcAppVersionPolicyStore implements AppVersionPolicyStore {

    static final String COLUMNS =
            "app_identity, platform, min_version, latest_version, store_url, message, updated_at, updated_by";

    static final String FIND_SQL = "SELECT " + COLUMNS + " FROM public.app_versions "
            + "WHERE app_identity = ? AND platform = ?";

    static final String FIND_ALL_SQL = "SELECT " + COLUMNS + " FROM public.app_versions "
            + "ORDER BY app_identity, platform";

    static final String UPSERT_SQL = """
            INSERT INTO public.app_versions (app_identity, platform, min_version, latest_version, store_url, message,
                updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (app_identity, platform) DO UPDATE SET min_version = EXCLUDED.min_version,
                latest_version = EXCLUDED.latest_version, store_url = EXCLUDED.store_url, message = EXCLUDED.message,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            RETURNING (xmax = 0) AS created
            """;

    static final String DELETE_SQL = "DELETE FROM public.app_versions WHERE app_identity = ? AND platform = ? "
            + "RETURNING " + COLUMNS;

    private static final RowMapper<AppVersionPolicy> ROW = (rs, n) -> {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new AppVersionPolicy(rs.getString("app_identity"), AppPlatform.parse(rs.getString("platform")),
                rs.getString("min_version"), rs.getString("latest_version"), rs.getString("store_url"),
                rs.getString("message"), updatedAt != null ? updatedAt.toInstant() : null,
                rs.getObject("updated_by", UUID.class));
    };

    private final JdbcTemplate jdbc;

    public JdbcAppVersionPolicyStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<AppVersionPolicy> find(String appIdentity, AppPlatform platform) {
        return jdbc.query(FIND_SQL, ROW, appIdentity, platform.code()).stream().findFirst();
    }

    @Override
    public List<AppVersionPolicy> findAll() {
        return jdbc.query(FIND_ALL_SQL, ROW);
    }

    @Override
    public boolean upsert(AppVersionPolicy p) {
        Boolean created = jdbc.queryForObject(UPSERT_SQL, Boolean.class, p.appIdentity(), p.platform().code(),
                p.minVersion(), p.latestVersion(), p.storeUrl(), p.message(), Timestamp.from(p.updatedAt()),
                p.updatedBy());
        return Boolean.TRUE.equals(created);
    }

    @Override
    public Optional<AppVersionPolicy> delete(String appIdentity, AppPlatform platform) {
        return jdbc.query(DELETE_SQL, ROW, appIdentity, platform.code()).stream().findFirst();
    }
}
