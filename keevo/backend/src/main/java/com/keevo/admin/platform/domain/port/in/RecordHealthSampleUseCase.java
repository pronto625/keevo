package com.keevo.admin.platform.domain.port.in;

import com.keevo.admin.platform.domain.model.HealthSample;

/** RecordHealthSampleUseCase — 5-minute health sample (Story 9-5); purges samples older than 90 days. */
public interface RecordHealthSampleUseCase {

    HealthSample recordSample();
}
