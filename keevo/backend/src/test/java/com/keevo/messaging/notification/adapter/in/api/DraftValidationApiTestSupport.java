package com.keevo.messaging.notification.adapter.in.api;

import com.keevo.messaging.api.DraftValidationCommandPort;
import com.keevo.messaging.api.DraftValidationQueryPort;
import com.keevo.messaging.notification.domain.port.out.DraftNotificationRepository;

/**
 * Test-only access to the package-private {@code messaging :: api} draft adapter (Story 12.2e), for
 * tests of other packages that drive the real adapter over a mocked repository / use case.
 */
public final class DraftValidationApiTestSupport {

    private DraftValidationApiTestSupport() {}

    public static DraftValidationCommandPort commandPort(DraftNotificationRepository repository) {
        return new DraftValidationApiAdapter(repository);
    }

    public static DraftValidationQueryPort queryPort(DraftNotificationRepository repository) {
        return new DraftValidationApiAdapter(repository);
    }
}
