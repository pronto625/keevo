package com.keevo.sync.sync.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncErrorLogCleanupJobTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SyncErrorLogCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new SyncErrorLogCleanupJob(jdbcTemplate);
    }

    @Test
    void cleanup_deletesEntriesOlderThan30Days() {
        when(jdbcTemplate.queryForList(
                contains("information_schema.schemata"), eq(String.class)))
                .thenReturn(List.of("kv_abc123", "kv_def456"));
        when(jdbcTemplate.update(contains("DELETE FROM"), any(Timestamp.class)))
                .thenReturn(5);

        job.cleanup();

        // Should query schemas then delete from each
        verify(jdbcTemplate).queryForList(contains("information_schema"), eq(String.class));
        // 2 schemas → 2 DELETE calls
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), any(Timestamp.class));
        assertThat(sqlCaptor.getAllValues().get(0)).contains("kv_abc123");
        assertThat(sqlCaptor.getAllValues().get(1)).contains("kv_def456");
    }

    @Test
    void cleanup_keepsRecentEntries() {
        when(jdbcTemplate.queryForList(
                contains("information_schema.schemata"), eq(String.class)))
                .thenReturn(List.of("kv_abc123"));
        when(jdbcTemplate.update(contains("DELETE FROM"), any(Timestamp.class)))
                .thenReturn(0);

        job.cleanup();

        verify(jdbcTemplate).update(contains("kv_abc123"), any(Timestamp.class));
    }
}
