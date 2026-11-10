package com.keevo.whatsappcloud.template.adapter.out.persistence;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.whatsappcloud.template.domain.model.MessageTemplate;
import com.keevo.whatsappcloud.template.domain.model.TemplateReview;
import com.keevo.whatsappcloud.template.domain.port.out.MessageTemplateRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.5 — {@link MessageTemplateRepository} on the module table {@code whatsapp_templates} of the tenant schema
 * (validated, named in every statement), plain JDBC. A company keeps at most 100 templates: the list is a bounded scan;
 * name lookups use {@code uq_whatsapp_templates_name_language}, the webhook reviews one
 * {@code UPDATE … FROM unnest(…)} on {@code idx_whatsapp_templates_meta_id} / the unique name index.
 */
@Component
public class JdbcMessageTemplateRepository implements MessageTemplateRepository {

    private static final String COLUMNS = "id, name, language, category, header_format, header_text, header_handle, "
            + "body_text, body_examples, footer_text, status, rejected_reason, submit_error, meta_template_id, system, "
            + "created_by, created_at, updated_at";

    private static final RowMapper<MessageTemplate> TEMPLATE = (rs, n) -> {
        Array examples = rs.getArray("body_examples");
        return new MessageTemplate(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("language"),
                rs.getString("category"), rs.getString("header_format"), rs.getString("header_text"),
                rs.getString("header_handle"), rs.getString("body_text"),
                examples == null ? List.of() : Arrays.asList((String[]) examples.getArray()),
                rs.getString("footer_text"), rs.getString("status"), rs.getString("rejected_reason"),
                rs.getString("submit_error"), rs.getString("meta_template_id"), rs.getBoolean("system"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    };

    private final JdbcTemplate jdbc;

    public JdbcMessageTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String table(String schema) {
        TenantSchema.validate(schema);
        return "\"" + schema + "\".whatsapp_templates";
    }

    @Override
    public List<MessageTemplate> list(String schema) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + table(schema) + " ORDER BY created_at DESC, id LIMIT 200",
                TEMPLATE);
    }

    @Override
    public Optional<MessageTemplate> find(String schema, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + table(schema) + " WHERE id = ?", TEMPLATE, id).stream()
                .findFirst();
    }

    @Override
    public Optional<MessageTemplate> findByName(String schema, String name, String language) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + table(schema) + " WHERE name = ? AND language = ?",
                TEMPLATE, name, language).stream().findFirst();
    }

    @Override
    public long countOwned(String schema) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table(schema) + " WHERE NOT system", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public boolean insert(String schema, MessageTemplate t) {
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("INSERT INTO " + table(schema) + " (" + COLUMNS + ") VALUES "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                ps.setObject(1, t.id());
                ps.setString(2, t.name());
                ps.setString(3, t.language());
                ps.setString(4, t.category());
                ps.setString(5, t.headerFormat());
                ps.setString(6, t.headerText());
                ps.setString(7, t.headerHandle());
                ps.setString(8, t.bodyText());
                ps.setArray(9, con.createArrayOf("text", t.bodyExamples().toArray()));
                ps.setString(10, t.footerText());
                ps.setString(11, t.status());
                ps.setString(12, t.rejectedReason());
                ps.setString(13, t.submitError());
                ps.setString(14, t.metaTemplateId());
                ps.setBoolean(15, t.system());
                ps.setObject(16, t.createdBy());
                ps.setTimestamp(17, Timestamp.from(t.createdAt()));
                ps.setTimestamp(18, Timestamp.from(t.updatedAt()));
                return ps;
            });
            return true;
        } catch (DuplicateKeyException e) {
            return false; // uq_whatsapp_templates_name_language
        }
    }

    @Override
    public void updateSubmission(String schema, MessageTemplate t) {
        jdbc.update("UPDATE " + table(schema) + " SET status = ?, rejected_reason = ?, submit_error = ?, "
                        + "meta_template_id = ?, updated_at = ? WHERE id = ?", t.status(), t.rejectedReason(),
                t.submitError(), t.metaTemplateId(), Timestamp.from(t.updatedAt()), t.id());
    }

    @Override
    public int applyReviews(String schema, List<TemplateReview> reviews) {
        if (reviews.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE " + table(schema) + " t SET status = v.status, rejected_reason = "
                    + "v.reason, submit_error = NULL, meta_template_id = COALESCE(v.meta_id, t.meta_template_id), "
                    + "updated_at = now() FROM unnest(?::varchar[], ?::varchar[], ?::varchar[], ?::varchar[], "
                    + "?::varchar[]) AS v(meta_id, name, lang, status, reason) WHERE (t.meta_template_id = v.meta_id "
                    + "OR (t.name = v.name AND t.language = v.lang AND (t.status <> 'DRAFT' OR t.system))) "
                    + "AND (t.status IS DISTINCT FROM v.status OR t.rejected_reason IS DISTINCT FROM v.reason "
                    + "OR t.meta_template_id IS NULL)");
            ps.setArray(1, con.createArrayOf("varchar", reviews.stream().map(TemplateReview::metaTemplateId).toArray()));
            ps.setArray(2, con.createArrayOf("varchar", reviews.stream().map(TemplateReview::name).toArray()));
            ps.setArray(3, con.createArrayOf("varchar", reviews.stream().map(TemplateReview::language).toArray()));
            ps.setArray(4, con.createArrayOf("varchar", reviews.stream().map(TemplateReview::status).toArray()));
            ps.setArray(5, con.createArrayOf("varchar", reviews.stream().map(TemplateReview::rejectedReason).toArray()));
            return ps;
        });
    }

    @Override
    public void delete(String schema, UUID id) {
        jdbc.update("DELETE FROM " + table(schema) + " WHERE id = ?", id);
    }

    @Override
    public void deleteOwnedByName(String schema, String name) {
        jdbc.update("DELETE FROM " + table(schema) + " WHERE name = ? AND NOT system", name);
    }

    @Override
    public void resetToDraft(String schema) {
        jdbc.update("UPDATE " + table(schema) + " SET status = 'DRAFT', meta_template_id = NULL, rejected_reason = "
                + "NULL, submit_error = NULL, header_handle = CASE WHEN system THEN NULL ELSE header_handle END, "
                + "updated_at = now() WHERE status <> 'DRAFT' OR meta_template_id IS NOT NULL");
    }
}
