package com.keevo.sync.sync.application.provider;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SyncTimestampsTest {

    private static final Instant AT = Instant.parse("2026-10-14T19:00:00.123456Z");

    @Test
    void instantsAreIsoUtcWhateverTheJdbcType() {
        assertThat(SyncTimestamps.iso(Timestamp.from(AT))).isEqualTo("2026-10-14T19:00:00.123456Z");
        assertThat(SyncTimestamps.iso(AT)).isEqualTo("2026-10-14T19:00:00.123456Z");
        assertThat(SyncTimestamps.iso(AT.atOffset(ZoneOffset.ofHours(1))))
                .isEqualTo("2026-10-14T19:00:00.123456Z");
        assertThat(SyncTimestamps.iso(OffsetDateTime.parse("2026-10-14T20:00:00+01:00")))
                .isEqualTo("2026-10-14T19:00:00Z");
    }

    @Test
    void calendarDaysKeepTheirDayForm() {
        assertThat(SyncTimestamps.iso(java.sql.Date.valueOf("2026-10-14"))).isEqualTo("2026-10-14");
        assertThat(SyncTimestamps.iso(LocalDate.of(2026, 10, 14))).isEqualTo("2026-10-14");
    }

    @Test
    void nullStaysNull() {
        assertThat(SyncTimestamps.iso(null)).isNull();
    }
}
