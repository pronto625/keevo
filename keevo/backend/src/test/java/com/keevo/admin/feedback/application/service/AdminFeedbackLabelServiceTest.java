package com.keevo.admin.feedback.application.service;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.TenantBranch;
import com.keevo.admin.crosstenant.TenantTable;
import com.keevo.admin.feedback.application.service.AdminFeedbackLabelService.FeedbackLabels;
import com.keevo.feedback.api.FeedbackView;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/** Spec 9-1 completion — company name and submitter label of the feedback inbox. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminFeedbackLabelServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private CrossTenantSql crossTenant;
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    private AdminFeedbackLabelService service;

    private final UUID owner = UUID.randomUUID();
    private final UUID employee = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new AdminFeedbackLabelService(jdbc, crossTenant);
        rows("FROM public.tenants WHERE", List.of(Map.of("id", TENANT_ID, "schema_name", "kv_abc123",
                "name", "Boutique Awa")));
        rows("m.role = 'OWNER'", List.of(Map.of("schema_name", "kv_abc123", "user_id", owner.toString())));
        when(crossTenant.withPresence(anyList())).thenAnswer(inv -> ((List<TenantBranch>) inv.getArgument(0))
                .stream().map(t -> t.withTables(java.util.EnumSet.allOf(TenantTable.class))).toList());
        // Story 19.11c: the employee names of every company of the page come from one union
        when(crossTenant.query(anyList(), any(), any(), any()))
                .thenReturn(java.util.Collections.singletonList(new String[]{TENANT_ID, employee.toString(), "Awa Diallo"}));
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

    private static FeedbackView feedback(String schema, UUID user) {
        return new FeedbackView(UUID.randomUUID(), "BUG", "Le scanner plante", schema, user,
                "1.0", "android", "/pos", Instant.now(), "NORMAL", null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void labels_tenantNameAndUser() {
        FeedbackView byEmployee = feedback("kv_abc123", employee);
        FeedbackView byOwner = feedback("kv_abc123", owner);
        FeedbackView byStranger = feedback("kv_abc123", stranger);

        Map<UUID, FeedbackLabels> labels = service.labels(List.of(byEmployee, byOwner, byStranger));

        assertThat(labels.get(byEmployee.id())).isEqualTo(new FeedbackLabels("Boutique Awa", "Awa Diallo"));
        assertThat(labels.get(byOwner.id())).isEqualTo(new FeedbackLabels("Boutique Awa", "Propriétaire"));
        assertThat(labels.get(byStranger.id()).userLabel()).isEqualTo("Utilisateur inconnu");
        // one union for the companies of the page, never a statement per company; branch bound to its users
        org.mockito.ArgumentCaptor<List<TenantBranch>> tenants = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<CrossTenantSql.BranchSql> branch =
                org.mockito.ArgumentCaptor.forClass(CrossTenantSql.BranchSql.class);
        verify(crossTenant, times(1)).query(tenants.capture(), branch.capture(), any(), any());
        assertThat(tenants.getValue()).extracting(TenantBranch::schemaName).containsExactly("kv_abc123");
        List<Object> params = new java.util.ArrayList<>();
        assertThat(branch.getValue().sql(tenants.getValue().get(0), params)).contains("kv_abc123.employees")
                .contains("user_id IN (?,?,?)");
        assertThat(params).containsExactlyInAnyOrder(employee, owner, stranger);
    }

    @Test
    void labels_deletedOrUnknownTenant_isEntrepriseSupprimee() {
        FeedbackView gone = feedback("kv_zzz999", employee);
        FeedbackView invalid = feedback("not-a-schema", employee);

        Map<UUID, FeedbackLabels> labels = service.labels(List.of(gone, invalid));

        assertThat(labels.get(gone.id()).tenantName()).isEqualTo("Entreprise supprimée");
        assertThat(labels.get(invalid.id()).tenantName()).isEqualTo("Entreprise supprimée");
        verify(jdbc, never()).query(contains("not-a-schema"), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void labels_schemaErrorKeepsTenantName_connectionFailureRethrown() {
        FeedbackView f = feedback("kv_abc123", employee);
        // the company's schema fails: CrossTenantSql skips it, the union answers no name
        when(crossTenant.query(anyList(), any(), any(), any())).thenReturn(List.of());

        assertThat(service.labels(List.of(f)).get(f.id()))
                .isEqualTo(new FeedbackLabels("Boutique Awa", "Utilisateur inconnu"));

        when(crossTenant.query(anyList(), any(), any(), any()))
                .thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        assertThatThrownBy(() -> service.labels(List.of(f))).isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    @Test
    void labels_emptyPage_noQuery() {
        assertThat(service.labels(List.of())).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
