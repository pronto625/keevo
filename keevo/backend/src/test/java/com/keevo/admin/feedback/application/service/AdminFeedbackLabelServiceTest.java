package com.keevo.admin.feedback.application.service;

import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService.FeedbackLabels;
import com.keevo.feedback.feedback.domain.model.Feedback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/** Spec 9-1 completion — company name and submitter label of the feedback inbox. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminFeedbackLabelServiceTest {

    @Mock private JdbcTemplate jdbc;

    private AdminFeedbackLabelService service;

    private final UUID owner = UUID.randomUUID();
    private final UUID employee = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminFeedbackLabelService(jdbc);
        rows("FROM public.tenants WHERE", List.of(Map.of("schema_name", "kv_abc123", "name", "Boutique Awa")));
        rows("m.role = 'OWNER'", List.of(Map.of("schema_name", "kv_abc123", "user_id", owner.toString())));
        rows("kv_abc123.employees", List.of(Map.of("user_id", employee.toString(), "full_name", "Awa Diallo")));
    }

    private void rows(String fragment, List<Map<String, String>> rows) {
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            for (Map<String, String> r : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(org.mockito.ArgumentMatchers.anyString()))
                        .thenAnswer(a -> r.get(a.<String>getArgument(0)));
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains(fragment), any(RowCallbackHandler.class), any(Object[].class));
    }

    private static Feedback feedback(String schema, UUID user) {
        return new Feedback(UUID.randomUUID(), "BUG", "Le scanner plante", schema, user,
                "1.0", "android", "/pos", Instant.now(), "NORMAL");
    }

    @Test
    void labels_tenantNameAndUser() {
        Feedback byEmployee = feedback("kv_abc123", employee);
        Feedback byOwner = feedback("kv_abc123", owner);
        Feedback byStranger = feedback("kv_abc123", stranger);

        Map<UUID, FeedbackLabels> labels = service.labels(List.of(byEmployee, byOwner, byStranger));

        assertThat(labels.get(byEmployee.getId())).isEqualTo(new FeedbackLabels("Boutique Awa", "Awa Diallo"));
        assertThat(labels.get(byOwner.getId())).isEqualTo(new FeedbackLabels("Boutique Awa", "Propriétaire"));
        assertThat(labels.get(byStranger.getId()).userLabel()).isEqualTo("Utilisateur inconnu");
        // one employees query for the one schema of the page
        verify(jdbc, times(1)).query(contains(".employees"), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void labels_deletedOrUnknownTenant_isEntrepriseSupprimee() {
        Feedback gone = feedback("kv_zzz999", employee);
        Feedback invalid = feedback("not-a-schema", employee);

        Map<UUID, FeedbackLabels> labels = service.labels(List.of(gone, invalid));

        assertThat(labels.get(gone.getId()).tenantName()).isEqualTo("Entreprise supprimée");
        assertThat(labels.get(invalid.getId()).tenantName()).isEqualTo("Entreprise supprimée");
        verify(jdbc, never()).query(contains("not-a-schema"), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void labels_schemaErrorKeepsTenantName_connectionFailureRethrown() {
        Feedback f = feedback("kv_abc123", employee);
        doThrow(new BadSqlGrammarException("x", "sql", new SQLException("relation does not exist")))
                .when(jdbc).query(contains(".employees"), any(RowCallbackHandler.class), any(Object[].class));

        assertThat(service.labels(List.of(f)).get(f.getId()))
                .isEqualTo(new FeedbackLabels("Boutique Awa", "Utilisateur inconnu"));

        doThrow(new CannotGetJdbcConnectionException("pool exhausted"))
                .when(jdbc).query(contains(".employees"), any(RowCallbackHandler.class), any(Object[].class));
        assertThatThrownBy(() -> service.labels(List.of(f))).isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    @Test
    void labels_emptyPage_noQuery() {
        assertThat(service.labels(List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
