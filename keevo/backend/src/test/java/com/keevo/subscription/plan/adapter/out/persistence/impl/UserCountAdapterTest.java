package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Device test: the subscription screen showed « Employés 0 » with one active employee. */
class UserCountAdapterTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void countsActiveEmployeesOfTheTenantEmployeesTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Integer.class))).thenReturn(1);
        TenantContext.setCurrentTenant("kv_abc123");

        assertThat(new UserCountAdapter(jdbc).countEmployees()).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Integer.class));
        assertThat(sql.getValue())
                .contains("\"kv_abc123\".employees")
                .contains("status = 'ACTIVE'");
    }
}
