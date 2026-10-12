package com.keevo.reporting.export.adapter.out.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExportCells (v1s-18-1)")
class ExportCellsTest {

    private static final Instant AT = Instant.parse("2025-12-31T23:30:00Z"); // 01/01/2026 00:30 WAT

    @Test
    @DisplayName("toLong rounds half-up: BigDecimal, Double, Float")
    void toLongRounds() {
        assertThat(ExportCells.toLong(new BigDecimal("2.5"))).isEqualTo(3);
        assertThat(ExportCells.toLong(2.5d)).isEqualTo(3);
        assertThat(ExportCells.toLong(2.4f)).isEqualTo(2);
        assertThat(ExportCells.toLong(1999.6d)).isEqualTo(2000);
        assertThat(ExportCells.toLong(7)).isEqualTo(7);
    }

    @Test
    @DisplayName("toWatDateTime / toWatDate accept Instant, OffsetDateTime, ZonedDateTime, Date, Timestamp")
    void temporalTypes() {
        LocalDateTime wat = LocalDateTime.of(2026, 1, 1, 0, 30);
        for (Object v : new Object[]{AT, OffsetDateTime.ofInstant(AT, ZoneOffset.UTC),
                ZonedDateTime.ofInstant(AT, ZoneOffset.ofHours(3)), Date.from(AT), Timestamp.from(AT)}) {
            assertThat(ExportCells.toWatDateTime(v)).as(v.getClass().getSimpleName()).isEqualTo(wat);
            assertThat(ExportCells.toWatDate(v)).as(v.getClass().getSimpleName()).isEqualTo(LocalDate.of(2026, 1, 1));
        }
        assertThat(ExportCells.toWatDate(java.sql.Date.valueOf("2026-02-03"))).isEqualTo(LocalDate.of(2026, 2, 3));
    }

    @Test
    @DisplayName("safeText: formulas prefixed, numeric-like text kept")
    void safeText() {
        assertThat(ExportCells.safeText("=1+1")).isEqualTo("'=1+1");
        assertThat(ExportCells.safeText("+237 6 99 00 00 00")).isEqualTo("+237 6 99 00 00 00");
        assertThat(ExportCells.safeText("-12.5")).isEqualTo("-12.5");
        assertThat(ExportCells.safeText("-x")).isEqualTo("'-x");
        assertThat(ExportCells.safeText("Pagne")).isEqualTo("Pagne");
    }
}
