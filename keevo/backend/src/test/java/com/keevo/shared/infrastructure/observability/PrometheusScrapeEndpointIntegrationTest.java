package com.keevo.shared.infrastructure.observability;

import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.web.GlobalExceptionHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 11.2 — the real application context (Prometheus registry, OTel tracing bridge, OTLP export
 * off as by default, the production security chain) on the shared Testcontainers PostgreSQL.
 *
 * <p>Covers: scrape 200 with HTTP/JVM/Hikari/business metrics, 401 without the right token, the
 * health endpoint still public and UP without a collector, sales counted after commit only, the
 * sync-lag SQL on PostgreSQL, and the X-Request-Id echo = OTel trace id when a span is current.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability
@TestPropertySource(properties = "keevo.observability.prometheus-token=it-scrape-token")
@DisplayName("/actuator/prometheus — full context (Story 11.2)")
class PrometheusScrapeEndpointIntegrationTest extends FullContextIntegrationTest {

    private static final String TOKEN = "Bearer it-scrape-token";

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SyncLagMetrics syncLagMetrics;
    @Autowired GlobalExceptionHandler globalExceptionHandler;

    private static SaleCompletedEvent sale(String tenant, int total) {
        return new SaleCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), tenant,
                UUID.randomUUID(), total, 0, "[]", Instant.now(), null, "CASH", java.util.List.of());
    }

    private double sales(String tenant) {
        Counter c = meterRegistry.find("keevo.sales").tag("tenant", tenant).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("health public and UP with no collector; X-Request-Id echoes the OTel trace id")
    void healthUpAndTraceIdEchoed() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andReturn();

        // 32 lowercase hex chars = W3C/OTel trace id (the 11.1 fallback would be a dashed UUID)
        assertThat(result.getResponse().getHeader("X-Request-Id")).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("inbound traceparent / B3 headers are ignored: the trace id is server-generated")
    void inboundPropagationHeadersNotConsumed() throws Exception {
        String clientTraceId = "0af7651916cd43dd8448eb211c80319c";
        String echoed = mockMvc.perform(get("/actuator/health")
                        .header("traceparent", "00-" + clientTraceId + "-b7ad6b7169203331-01")
                        .header("b3", clientTraceId + "-b7ad6b7169203331-1")
                        .header("X-B3-TraceId", clientTraceId)
                        .header("X-B3-SpanId", "b7ad6b7169203331")
                        .header("X-B3-Sampled", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Request-Id");

        assertThat(echoed).matches("[0-9a-f]{32}").isNotEqualTo(clientTraceId);
    }

    @Test
    @DisplayName("the Spring-built GlobalExceptionHandler counts errors in the scraped registry")
    void domainErrorCountedInScrape() throws Exception {
        globalExceptionHandler.handleDomainException(new DomainException(ErrorCode.AUDIT_IMMUTABLE, "it"));

        String body = mockMvc.perform(get("/actuator/prometheus").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("keevo_errors_total{domainCode=\"AUDIT_IMMUTABLE\"}");
    }

    @RepeatedTest(10)
    @DisplayName("trace id echoed for unsampled requests too (default sampling 0.1)")
    void traceIdEchoedRegardlessOfSampling() throws Exception {
        String echoed = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(echoed).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("client X-Request-Id is echoed back unchanged")
    void clientRequestIdEchoed() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health").header("X-Request-Id", "client-abc-1"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("client-abc-1");
    }

    @Test
    @DisplayName("scrape with the token → 200 with HTTP, JVM, Hikari and business metrics")
    void scrapeOk() throws Exception {
        String tenant = "kv_it" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        transactionTemplate.executeWithoutResult(tx -> publisher.publishEvent(sale(tenant, 4200)));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()); // seeds http.server.requests

        String body = mockMvc.perform(get("/actuator/prometheus").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("http_server_requests")
                .contains("jvm_")
                .contains("hikaricp_")
                .contains("keevo_sales_total{tenant=\"" + tenant + "\"} 1.0")
                .contains("keevo_sales_revenue_total{tenant=\"" + tenant + "\"} 4200.0")
                .contains("keevo_sync_lag_seconds");
    }

    @Test
    @DisplayName("scrape with a wrong or missing token → 401, no metrics body")
    void scrapeUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("/actuator/metrics is never anonymous")
    void metricsNotAnonymous() throws Exception {
        int status = mockMvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }

    @Test
    @DisplayName("sale committed → counted; sale rolled back → counters unchanged")
    void countedAfterCommitOnly() {
        String tenant = "kv_tx" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        transactionTemplate.executeWithoutResult(tx -> {
            publisher.publishEvent(sale(tenant, 1000));
            assertThat(sales(tenant)).as("not counted before commit").isZero();
            tx.setRollbackOnly();
        });
        assertThat(sales(tenant)).as("rolled back").isZero();

        transactionTemplate.executeWithoutResult(tx -> publisher.publishEvent(sale(tenant, 1000)));
        assertThat(sales(tenant)).as("committed").isEqualTo(1.0);
    }

    @Test
    @DisplayName("sync lag SQL runs on PostgreSQL: a push just now → lag of a few seconds at most")
    void syncLagFromMostRecentPush() {
        String deviceId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_push_at) "
                + "VALUES (?, ?, 'kv_lagtest', NOW())", deviceId, UUID.randomUUID());
        try {
            syncLagMetrics.refresh();
            assertThat(syncLagMetrics.currentLagSeconds()).isBetween(0.0, 60.0);
            assertThat(meterRegistry.get("keevo.sync.lag").gauge().value()).isBetween(0.0, 60.0);
        } finally {
            jdbcTemplate.update("DELETE FROM public.user_sync_state WHERE device_id = ?", deviceId);
        }
    }
}
