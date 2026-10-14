package com.keevo.sync.sync.application.provider;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Formats native-query date values for the sync pull payload.
 *
 * <p>{@code java.sql.Timestamp.toString()} renders the instant in the JVM default zone with no
 * offset ({@code 2026-10-14 10:00:00.123}); the app parses an offset-less string as device-local
 * time, so every pulled date was shifted by the server/device zone difference. Instants are
 * emitted as ISO-8601 UTC ({@code 2026-10-14T10:00:00.123Z}) instead. A calendar day
 * ({@code java.sql.Date}, {@code LocalDate}) keeps its {@code yyyy-MM-dd} form: it has no instant.
 */
final class SyncTimestamps {

    private SyncTimestamps() {}

    static String iso(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toInstant().toString();
        if (o instanceof Instant i) return i.toString();
        if (o instanceof OffsetDateTime odt) return odt.toInstant().toString();
        if (o instanceof ZonedDateTime zdt) return zdt.toInstant().toString();
        // timestamp without time zone: read in the JVM zone, like DeltaPageQuery's cursor
        if (o instanceof LocalDateTime ldt) return ldt.atZone(ZoneId.systemDefault()).toInstant().toString();
        return o.toString(); // java.sql.Date / LocalDate: yyyy-MM-dd
    }
}
