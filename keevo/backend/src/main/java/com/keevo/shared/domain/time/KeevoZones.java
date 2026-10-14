package com.keevo.shared.domain.time;

import java.time.ZoneId;

/** KeevoZones — the business time zone shared by plan management and its owner messages. */
public final class KeevoZones {

    /** West Africa Time, the zone of Keevo's business days. */
    public static final ZoneId WAT = ZoneId.of("Africa/Douala");

    private KeevoZones() {}
}
