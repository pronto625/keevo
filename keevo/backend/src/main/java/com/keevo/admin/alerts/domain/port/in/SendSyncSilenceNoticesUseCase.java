package com.keevo.admin.alerts.domain.port.in;

/**
 * SendSyncSilenceNoticesUseCase — FR81 proactive owner alert: WhatsApp to the owner of each company
 * whose devices have all been silent for 5 days or more, once per silence streak (Story 9-5).
 */
public interface SendSyncSilenceNoticesUseCase {

    /** @return number of WhatsApp messages sent */
    int execute();
}
