package com.keevo.messaging.channel.application;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import com.keevo.messaging.channel.domain.port.out.EmailProvider;
import com.keevo.messaging.channel.domain.port.out.SmsProvider;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.subscription.plan.adapter.out.persistence.impl.JdbcUsageCounterStore;
import com.keevo.subscription.plan.application.service.UsageQuotaService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Story 19.9 — metered tenant SMS / email on PostgreSQL with the real 19.8 quota service and counters (stub providers):
 * a delivered SMS counts exactly 1 on MONTH and DAY, a failed one 0, an exhausted quota refuses before any provider
 * call with the counters unchanged.
 */
@DisplayName("Metered tenant messaging — counted after provider success, on PostgreSQL (Story 19.9)")
class MeteredTenantMessagingPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_metered_messaging";
    private static final String PLAN = "TEST_SMS_PLAN";
    private static final String MONTH = "2026-11";
    private static final String DAY = "2026-11-04";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private JdbcUsageCounterStore store;
    private UsageQuotaService quotas;
    private UUID tenant;
    private String schema;
    private final AtomicInteger providerCalls = new AtomicInteger();
    private boolean providerSucceeds = true;
    private MeteredTenantMessaging messaging;

    @BeforeAll
    static void migratePublic() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration")
                .load().migrate();
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO public.plans (code, label, sort_order, visible, kind) VALUES (?, 'Test SMS', 99, "
                + "false, 'PAID') ON CONFLICT (code) DO NOTHING", PLAN);
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM public.plan_quotas WHERE plan_type = ?", PLAN);
        jdbc.update("INSERT INTO public.plan_quotas (plan_type, metric, period, quota_limit) VALUES (?, 'SMS', "
                + "'MONTH', 5), (?, 'EMAILS', 'MONTH', 5)", PLAN, PLAN);
        tenant = UUID.randomUUID();
        String code = tenant.toString().substring(0, 8);
        schema = "kv_" + code.substring(0, 6);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status) VALUES (?, now(), now(), ?, 1, 1, 1, ?, ?, 'ACTIVE')",
                tenant, code, PLAN, schema);
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T09:00:00Z"), ZoneOffset.UTC);
        store = new JdbcUsageCounterStore(jdbc);
        List<PlanDefinition> plans = new ArrayList<>(PlanCatalog.SEED.all());
        plans.add(new PlanDefinition(PlanType.of(PLAN), "Test SMS", null, 99, false, PlanKind.PAID, null));
        quotas = new UsageQuotaService(store, PlanCatalog.of(plans, PlanCatalog.SEED.settings()),
                clock);
        SmsProvider sms = new SmsProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public SendOutcome send(String phone, String text, String senderId) {
                providerCalls.incrementAndGet();
                return providerSucceeds ? SendOutcome.DELIVERED : SendOutcome.FAILED;
            }
        };
        EmailProvider email = new EmailProvider() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public SendOutcome send(EmailMessage message, String sender) {
                providerCalls.incrementAndGet();
                return providerSucceeds ? SendOutcome.DELIVERED : SendOutcome.FAILED;
            }
        };
        messaging = new MeteredTenantMessaging(new FailoverSmsSender(List.of(sms), "BrefPoint"),
                new FailoverEmailSender(List.of(email), "noreply@brefpoint.app", null), quotas,
                new DataSourceTransactionManager(dataSource));
    }

    private long used(UsageMetric metric, String key) {
        return store.used(tenant, metric, key);
    }

    private void preUse(UsageMetric metric, int n) {
        jdbc.update("INSERT INTO public.usage_counters (tenant_id, metric, period, period_key, used) VALUES (?, ?, 'MONTH', "
                + "?, ?)",
                tenant, metric.name(), MONTH, n);
    }

    @Test
    @DisplayName("quota left (2 of 5 used): delivered, MONTH = 3 and DAY = 1")
    void deliveredCountsOnce() {
        preUse(UsageMetric.SMS, 2);

        DeliveryResult result = messaging.sendSms(schema, SmsMessage.of("+237699000123", "Bonjour"));

        assertThat(result).isEqualTo(DeliveryResult.deliveredBy("stub"));
        assertThat(used(UsageMetric.SMS, MONTH)).isEqualTo(3);
        assertThat(used(UsageMetric.SMS, DAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("every provider fails: not delivered, nothing counted")
    void failedNotCounted() {
        providerSucceeds = false;

        assertThat(messaging.sendSms(schema, SmsMessage.of("+237699000123", "Bonjour")).delivered()).isFalse();
        assertThat(providerCalls).hasValue(1);
        assertThat(used(UsageMetric.SMS, MONTH)).isZero();
        assertThat(used(UsageMetric.SMS, DAY)).isZero();
    }

    @Test
    @DisplayName("quota exhausted (5 of 5, no pack): QUOTA_EXCEEDED from the probe, no provider call, counters unchanged")
    void exhaustedRefusedBeforeSending() {
        preUse(UsageMetric.SMS, 5);

        DomainException refused = catchThrowableOfType(DomainException.class,
                () -> messaging.sendSms(schema, SmsMessage.of("+237699000123", "Bonjour")));

        assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
        assertThat(providerCalls).hasValue(0);
        assertThat(used(UsageMetric.SMS, MONTH)).isEqualTo(5);
        assertThat(used(UsageMetric.SMS, DAY)).isZero();
    }

    private Long credits(UsageMetric metric) {
        List<Long> rows = jdbc.queryForList("SELECT remaining FROM public.usage_credits WHERE tenant_id = ? "
                + "AND metric = ?", Long.class, tenant, metric.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    @DisplayName("month used up with a purchased pack: the rolled-back probe leaves the pack untouched, the count "
            + "after delivery draws one credit; a failed send draws none")
    void packAfterMonth() {
        preUse(UsageMetric.SMS, 5);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(s -> quotas.add(tenant, UsageMetric.SMS, 2));

        providerSucceeds = false;
        assertThat(messaging.sendSms(schema, SmsMessage.of("+237699000123", "Bonjour")).delivered()).isFalse();
        assertThat(credits(UsageMetric.SMS)).as("probe rolled back, nothing counted").isEqualTo(2);

        providerSucceeds = true;
        assertThat(messaging.sendSms(schema, SmsMessage.of("+237699000123", "Bonjour")).delivered()).isTrue();
        assertThat(credits(UsageMetric.SMS)).as("one credit, drawn once").isEqualTo(1);
        assertThat(used(UsageMetric.SMS, MONTH)).isEqualTo(5);
    }

    @Test
    @DisplayName("email: counted on the EMAILS metric only")
    void emailCounted() {
        assertThat(messaging.sendEmail(schema, EmailMessage.of("awa@example.cm", "S", "T")).delivered()).isTrue();
        assertThat(used(UsageMetric.EMAILS, MONTH)).isEqualTo(1);
        assertThat(used(UsageMetric.SMS, MONTH)).isZero();
    }
}
