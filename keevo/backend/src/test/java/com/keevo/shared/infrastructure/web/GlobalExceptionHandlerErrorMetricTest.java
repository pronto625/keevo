package com.keevo.shared.infrastructure.web;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 11.2 — {@code keevo_errors_total{domainCode}} incremented by {@code handleDomainException}.
 */
@DisplayName("GlobalExceptionHandler — keevo_errors_total{domainCode}")
class GlobalExceptionHandlerErrorMetricTest {

    private static GlobalExceptionHandler handlerWith(MeterRegistry registry) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("meterRegistry", registry);
        return new GlobalExceptionHandler(factory.getBeanProvider(MeterRegistry.class));
    }

    @Test
    @DisplayName("domain error → counter +1 tagged with its domainCode, response unchanged")
    void countsByDomainCode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = handlerWith(registry);

        var resp = handler.handleDomainException(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "x"));
        handler.handleDomainException(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "y"));
        handler.handleDomainException(new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "z"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(registry.get("keevo.errors").tag("domainCode", "PLAN_LIMIT_EXCEEDED").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("keevo.errors").tag("domainCode", "RATE_LIMIT_EXCEEDED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("null domainCode → tag UNKNOWN")
    void nullCodeIsUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        handlerWith(registry).handleDomainException(new DomainException((String) null));

        assertThat(registry.get("keevo.errors").tag("domainCode", "UNKNOWN").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no MeterRegistry (slice tests, new GlobalExceptionHandler()) → response still built")
    void noRegistryStillResponds() {
        var resp = new GlobalExceptionHandler(new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class))
                .handleDomainException(new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "x"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(new GlobalExceptionHandler()
                .handleDomainException(new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "x"))
                .getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Prometheus exposition name: keevo_errors_total{domainCode}")
    void prometheusName() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        handlerWith(registry).handleDomainException(new DomainException(ErrorCode.PLAN_LIMIT_EXCEEDED, "x"));

        assertThat(registry.scrape()).contains("keevo_errors_total{domainCode=\"PLAN_LIMIT_EXCEEDED\"} 1.0");
    }

    @Test
    @DisplayName("non-domain handlers count the domainCode they return (500 INTERNAL_ERROR included)")
    void nonDomainHandlersCounted() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GlobalExceptionHandler handler = handlerWith(registry);

        handler.handleGeneral(new RuntimeException("boom"));
        handler.handleOptimisticLock(new OptimisticLockingFailureException("x"));
        handler.handleMissingParam(new MissingServletRequestParameterException("p", "String"));
        handler.handleAccessDenied(new AuthorizationDeniedException("no"));
        handler.handleNoResource(new NoResourceFoundException(
                HttpMethod.GET, "/nope"));
        handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("PATCH"));

        for (String code : new String[] {"INTERNAL_ERROR", "OPTIMISTIC_LOCK", "MISSING_PARAMETER",
                "FORBIDDEN", "NOT_FOUND", "METHOD_NOT_ALLOWED"}) {
            assertThat(registry.get("keevo.errors").tag("domainCode", code).counter().count())
                    .as(code).isEqualTo(1.0);
        }
    }
}
