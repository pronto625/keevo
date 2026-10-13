package com.keevo.admin.platform.domain.port.out;

import com.keevo.admin.platform.domain.model.TimerReading;

import java.util.List;

/** RuntimeMetricsPort — in-process metrics read by the health sampler (Story 9-5). */
public interface RuntimeMetricsPort {

    /** Every {@code http.server.requests} timer (one per tag combination). */
    List<TimerReading> httpServerTimers();

    /** Active connections of the database pool, null when unknown. */
    Integer activeDbConnections();

    /** Maximum size of the database pool, null when unknown. */
    Integer maxDbConnections();
}
