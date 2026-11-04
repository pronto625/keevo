package com.keevo.messaging.channel.application;

import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.capability.entitlement.UsageStatus;
import com.keevo.shared.capability.notification.DeliveryResult;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.shared.capability.notification.EmailSender;
import com.keevo.shared.capability.notification.SmsMessage;
import com.keevo.shared.capability.notification.SmsSender;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MeteredTenantMessaging — probe, send, count (Story 19.9)")
class MeteredTenantMessagingTest {

    private static final String SCHEMA = "kv_abc123";

    /** Ordered trace of transactions, quota calls and sends. */
    private final List<String> trace = new ArrayList<>();
    private final List<Supplier<RuntimeException>> quotaAnswers = new ArrayList<>();
    private DeliveryResult sendResult = DeliveryResult.deliveredBy("mboasms");

    @AfterEach
    void clearTx() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private final PlatformTransactionManager txManager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            trace.add("begin(" + definition.getPropagationBehavior() + ")");
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            trace.add(status.isRollbackOnly() ? "rollback" : "commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            trace.add("rollback");
        }
    };

    private final UsageQuotaPort quotas = new UsageQuotaPort() {
        @Override
        public void assertAndIncrement(UUID tenantId, UsageMetric metric, int n) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void assertAndIncrementForSchema(String tenantSchema, UsageMetric metric, int n) {
            trace.add("quota(" + tenantSchema + "," + metric + "," + n + ")");
            if (!quotaAnswers.isEmpty()) {
                RuntimeException e = quotaAnswers.remove(0).get();
                if (e != null) throw e;
            }
        }

        @Override
        public UsageStatus status(UUID tenantId) {
            return UsageStatus.empty();
        }

        @Override
        public UsageStatus statusForSchema(String tenantSchema) {
            return UsageStatus.empty();
        }
    };

    private final SmsSender sms = new SmsSender() {
        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public DeliveryResult send(SmsMessage message) {
            trace.add("sms(active=" + TransactionSynchronizationManager.isActualTransactionActive() + ")");
            return sendResult;
        }
    };

    private final EmailSender email = new EmailSender() {
        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public DeliveryResult send(EmailMessage message) {
            trace.add("email");
            return sendResult;
        }
    };

    private final MeteredTenantMessaging messaging = new MeteredTenantMessaging(sms, email, quotas, txManager);

    private static DomainException quotaExceeded() {
        return new DomainException(ErrorCode.QUOTA_EXCEEDED, "quota");
    }

    @Test
    @DisplayName("delivered → probe rolled back, send outside any transaction, then one committed count")
    void delivered() {
        DeliveryResult result = messaging.sendSms(SCHEMA, SmsMessage.of("+237699000123", "x"));

        assertThat(result.delivered()).isTrue();
        int requiresNew = TransactionDefinition.PROPAGATION_REQUIRES_NEW;
        assertThat(trace).containsExactly(
                "begin(" + requiresNew + ")", "quota(kv_abc123,SMS,1)", "rollback",
                "sms(active=false)",
                "begin(" + requiresNew + ")", "quota(kv_abc123,SMS,1)", "commit");
    }

    @Test
    @DisplayName("email uses the EMAILS metric")
    void emailMetric() {
        messaging.sendEmail(SCHEMA, EmailMessage.of("a@b.cm", "S", "T"));

        assertThat(trace).filteredOn(t -> t.startsWith("quota")).containsOnly("quota(kv_abc123,EMAILS,1)")
                .hasSize(2);
    }

    @Test
    @DisplayName("probe refused → QUOTA_EXCEEDED, nothing sent, nothing counted")
    void probeRefused() {
        quotaAnswers.add(MeteredTenantMessagingTest::quotaExceeded);

        assertThatThrownBy(() -> messaging.sendSms(SCHEMA, SmsMessage.of("+237699000123", "x")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));
        assertThat(trace).noneMatch(t -> t.startsWith("sms")).doesNotContain("commit");
    }

    @Test
    @DisplayName("send failed → not delivered, never counted")
    void failedNotCounted() {
        sendResult = DeliveryResult.notDelivered();

        assertThat(messaging.sendSms(SCHEMA, SmsMessage.of("+237699000123", "x")).delivered()).isFalse();
        assertThat(trace).filteredOn(t -> t.startsWith("quota")).hasSize(1);
        assertThat(trace).doesNotContain("commit");
    }

    @Test
    @DisplayName("post-send refusal (concurrent send took the last unit) → logged and absorbed, still delivered")
    void postSendRefusalAbsorbed() {
        quotaAnswers.add(() -> null);
        quotaAnswers.add(MeteredTenantMessagingTest::quotaExceeded);

        assertThat(messaging.sendSms(SCHEMA, SmsMessage.of("+237699000123", "x")).delivered()).isTrue();
    }

    @Test
    @DisplayName("post-send infrastructure failure (not a quota refusal) → absorbed, still delivered")
    void postSendInfrastructureFailureAbsorbed() {
        quotaAnswers.add(() -> null);
        quotaAnswers.add(() -> new IllegalStateException("connection lost"));

        assertThat(messaging.sendSms(SCHEMA, SmsMessage.of("+237699000123", "x")).delivered()).isTrue();
    }

    @Test
    @DisplayName("called inside an active transaction → IllegalStateException before any probe or send")
    void activeTransactionGuard() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> messaging.sendSms(SCHEMA, SmsMessage.of("+237699000123", "x")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(trace).isEmpty();
    }
}
