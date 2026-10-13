package com.keevo.admin.alerts.domain.port.in;

/** PurgeAlertAcksUseCase — deletes alert acks older than 90 days (Story 9-5). */
public interface PurgeAlertAcksUseCase {

    /** @return number of acks deleted */
    int purgeOldAcks();
}
