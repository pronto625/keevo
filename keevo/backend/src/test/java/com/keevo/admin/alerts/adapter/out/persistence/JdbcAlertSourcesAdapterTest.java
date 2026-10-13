package com.keevo.admin.alerts.adapter.out.persistence;

import com.keevo.admin.alerts.adapter.out.persistence.JdbcAlertSourcesAdapter.LiveTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("JdbcAlertSourcesAdapter.perCompany (Story 9-5)")
class JdbcAlertSourcesAdapterTest {

    final JdbcAlertSourcesAdapter adapter = new JdbcAlertSourcesAdapter(mock(JdbcTemplate.class));
    final LiveTenant tenant = new LiveTenant(UUID.randomUUID(), "Boutique", "kv_aaa111");

    @Test
    @DisplayName("the company's own SQL error skips it (null)")
    void badSqlSkips() {
        Object result = adapter.perCompany(tenant, () -> {
            throw new BadSqlGrammarException("q", "sql", new SQLException("no table"));
        });
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("connection / resource failures propagate")
    void connectionFailuresPropagate() {
        assertThatThrownBy(() -> adapter.perCompany(tenant, () -> {
            throw new DataAccessResourceFailureException("down");
        })).isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> adapter.perCompany(tenant, () -> {
            throw new QueryTimeoutException("slow");
        })).isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void valuePassesThrough() {
        assertThat(adapter.perCompany(tenant, () -> 7)).isEqualTo(7);
    }
}
