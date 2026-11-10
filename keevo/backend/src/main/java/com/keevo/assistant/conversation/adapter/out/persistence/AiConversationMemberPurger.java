package com.keevo.assistant.conversation.adapter.out.persistence;

import com.keevo.shared.domain.port.out.MemberDataPurger;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Story 16.6 (RGPD, reprise 2026-11-10; item routed by the 21.4 review) — a member removed from a company loses their
 * saved assistant conversations there ({@code "kv_xxxxxx".ai_conversations}: free-text questions, client names and
 * phones copied from guided answers). The table only exists once the module was provisioned for the company
 * ({@code to_regclass} → no-op otherwise); the delete runs whatever the module's state (DISABLED included): these are
 * the removed person's data, not module data. Two queries: the catalogue lookup and one delete on the indexed
 * {@code user_id} ({@code idx_ai_conversations_user_updated}).
 */
@Component
public class AiConversationMemberPurger implements MemberDataPurger {

    private final JdbcTemplate jdbc;

    public AiConversationMemberPurger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void purgeMember(String tenantSchema, UUID userId) {
        TenantSchema.validate(tenantSchema);
        String table = "\"" + tenantSchema + "\"." + JdbcAiConversationRepository.TABLE;
        String present = jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table);
        if (present == null) {
            return;
        }
        jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
    }
}
