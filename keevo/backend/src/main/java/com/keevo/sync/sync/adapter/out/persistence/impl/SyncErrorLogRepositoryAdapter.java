package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.sync.sync.domain.model.SyncErrorLogEntry;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SyncErrorLogRepositoryAdapter — JdbcTemplate implementation of {@link SyncErrorLogRepository}.
 *
 * <p>Operates within the TENANT schema (NOT public). TenantContext must be set
 * by JwtAuthFilter before any call. The JdbcTemplate resolves against the
 * current {@code search_path} set by TenantConnectionProvider.
 *
 * <p>Story 5.5 — AC5.
 */
@Repository
public class SyncErrorLogRepositoryAdapter implements SyncErrorLogRepository {

    private static final Logger log = LoggerFactory.getLogger(SyncErrorLogRepositoryAdapter.class);

    private static final String INSERT_SQL =
            "INSERT INTO sync_error_log (id, operation_id, operation_type, entity_id, payload, error_reason, client_timestamp, created_at) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, NOW())";

    private static final String FIND_BY_OP_ID_SQL =
            "SELECT id, operation_id, operation_type, entity_id, payload, error_reason, client_timestamp, created_at "
                    + "FROM sync_error_log WHERE operation_id = ?";

    private static final String DELETE_OLDER_SQL =
            "DELETE FROM sync_error_log WHERE created_at < ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SyncErrorLogRepositoryAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(SyncErrorLogEntry entry) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(entry.payload());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payload for operation {}: {}", entry.operationId(), e.getMessage());
            payloadJson = "{}";
        }
        jdbcTemplate.update(INSERT_SQL,
                entry.id(),
                entry.operationId(),
                entry.operationType(),
                entry.entityId(),
                payloadJson,
                entry.errorReason(),
                entry.clientTimestamp() != null ? Timestamp.from(entry.clientTimestamp()) : null);
    }

    @Override
    public Optional<SyncErrorLogEntry> findByOperationId(String operationId) {
        List<SyncErrorLogEntry> results = jdbcTemplate.query(FIND_BY_OP_ID_SQL, rowMapper(), operationId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update(DELETE_OLDER_SQL, Timestamp.from(cutoff));
    }

    private RowMapper<SyncErrorLogEntry> rowMapper() {
        return (rs, rowNum) -> new SyncErrorLogEntry(
                UUID.fromString(rs.getString("id")),
                rs.getString("operation_id"),
                rs.getString("operation_type"),
                rs.getString("entity_id"),
                parsePayload(rs.getString("payload")),
                rs.getString("error_reason"),
                toInstant(rs, "client_timestamp"),
                toInstant(rs, "created_at"));
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse sync_error_log payload: {}", e.getMessage());
            return Map.of();
        }
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }
}
