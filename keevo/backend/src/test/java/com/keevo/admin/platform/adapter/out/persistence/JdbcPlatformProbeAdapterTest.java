package com.keevo.admin.platform.adapter.out.persistence;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JdbcPlatformProbeAdapter (Story 9-5, 19.11c)")
class JdbcPlatformProbeAdapterTest {

    private static final TenantBranch WITH_LOG = new TenantBranch("11111111-1111-1111-1111-111111111111", "A",
            "kv_aaa111", "ACTIVE", null, EnumSet.allOf(TenantTable.class));
    private static final TenantBranch WITHOUT_LOG = new TenantBranch("22222222-2222-2222-2222-222222222222", "B",
            "kv_bbb222", "ACTIVE", null, Set.of(TenantTable.SALES));

    CrossTenantSql crossTenant;
    DataSource dataSource;
    JdbcPlatformProbeAdapter adapter;

    @BeforeEach
    void setUp() {
        crossTenant = mock(CrossTenantSql.class);
        dataSource = mock(DataSource.class);
        adapter = new JdbcPlatformProbeAdapter(crossTenant, dataSource);
        when(crossTenant.liveTenants()).thenReturn(List.of(WITH_LOG, WITHOUT_LOG));
    }

    @Test
    @DisplayName("databaseOk() is false when the check throws (here: no connection)")
    void databaseDown() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("refused"));
        assertThat(adapter.databaseOk()).isFalse();
    }

    @Test
    @DisplayName("refusals: one union over the companies holding the table, chunk sums added")
    @SuppressWarnings("unchecked")
    void sumsChunks_onlyCompaniesWithTheTable() {
        when(crossTenant.query(anyList(), any(), any(), any())).thenReturn(List.of(4L, 3L));

        assertThat(adapter.syncRefusalsSince(Instant.now())).isEqualTo(7);

        ArgumentCaptor<List<TenantBranch>> tenants = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<CrossTenantSql.BranchSql> branch = ArgumentCaptor.forClass(CrossTenantSql.BranchSql.class);
        verify(crossTenant).query(tenants.capture(), branch.capture(), any(), any());
        assertThat(tenants.getValue()).containsExactly(WITH_LOG);
        List<Object> params = new ArrayList<>();
        assertThat(branch.getValue().sql(WITH_LOG, params)).contains("\"kv_aaa111\".sync_error_log");
        assertThat(params).hasSize(1);
    }

    @Test
    @DisplayName("a connection failure propagates (never a falsely low count)")
    void connectionFailurePropagates() {
        when(crossTenant.query(anyList(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> adapter.syncRefusalsSince(Instant.now()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
