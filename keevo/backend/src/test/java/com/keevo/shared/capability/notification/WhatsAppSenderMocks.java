package com.keevo.shared.capability.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.lenient;

/**
 * Story 19.9c — test helper: a Mockito {@link WhatsAppSender} mock whose {@link WhatsAppSender#sendNotification}
 * runs its port default (free text through {@link WhatsAppSender#sendReport}), so a caller test keeps verifying the
 * exact free text with {@code verify(mock).sendReport(phone, text)} and stubbing failures on {@code sendReport}.
 * The template routing itself is tested in {@code ChainWhatsAppSenderTest}.
 */
public final class WhatsAppSenderMocks {

    private WhatsAppSenderMocks() {
    }

    /** Makes {@code mock.sendNotification} forward to {@code mock.sendReport}; returns the mock. */
    public static <T extends WhatsAppSender> T forwardNotifications(T mock) {
        lenient().doCallRealMethod().when(mock).sendNotification(any(), any());
        return mock;
    }
}
