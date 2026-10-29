package com.keevo.reporting.report.adapter.in.api;

import com.keevo.identity.api.DailyReportTextRequest;
import com.keevo.reporting.report.application.service.DailyReportFormatter;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Story 12.2e — reporting's implementation of {@code identity.api.DailyReportTextPort}. */
@ExtendWith(MockitoExtension.class)
class DailyReportTextAdapterTest {

    private static final String TENANT = "kv_test01";

    @Mock EndOfDayReportBuilder builder;
    @Mock DailyReportFormatter formatter;

    private DailyReportTextAdapter adapter;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        adapter = new DailyReportTextAdapter(builder, formatter);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void buildsThenFormatsWithTheSameArguments() {
        UUID storeId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-29T00:00:00Z");
        Instant to = Instant.parse("2026-10-29T18:00:00Z");
        LocalDate date = LocalDate.of(2026, 10, 29);
        LocalTime time = LocalTime.of(18, 0);
        EndOfDayReportData data = new EndOfDayReportData(
                "Boutique", date, time, false, false,
                5, 75000, 50000, 25000, 15000, List.of(), List.of(), 3, 0, 0, null);
        when(builder.build(storeId, from, to, "Boutique", date, time, false)).thenReturn(data);
        when(formatter.format(data)).thenReturn("📊 Rapport");

        String text = adapter.dailyReportText(TENANT,
                new DailyReportTextRequest(storeId, "Boutique", from, to, date, time, false));

        assertThat(text).isEqualTo("📊 Rapport");
    }

    @Test
    void otherTenantFailsFast() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> adapter.dailyReportText("kv_other1", new DailyReportTextRequest(
                UUID.randomUUID(), "B", now, now, LocalDate.now(), LocalTime.now(), false)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(builder, formatter);
    }

    @Test
    void requestRequiresStoreAndWindow() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new DailyReportTextRequest(null, "B", now, now, LocalDate.now(), LocalTime.now(), false))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new DailyReportTextRequest(UUID.randomUUID(), "B", null, now, LocalDate.now(), LocalTime.now(), false))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void requestRejectsBlankStoreNameAndInvertedWindow() {
        Instant now = Instant.now();
        assertValidationError(() -> new DailyReportTextRequest(UUID.randomUUID(), " ", now, now, LocalDate.now(), LocalTime.now(), false));
        assertValidationError(() -> new DailyReportTextRequest(UUID.randomUUID(), null, now, now, LocalDate.now(), LocalTime.now(), false));
        assertValidationError(() -> new DailyReportTextRequest(UUID.randomUUID(), "B", now.plusSeconds(1), now,
                LocalDate.now(), LocalTime.now(), false));
    }

    private static void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(com.keevo.shared.domain.exception.ErrorCode.VALIDATION_ERROR);
    }
}
