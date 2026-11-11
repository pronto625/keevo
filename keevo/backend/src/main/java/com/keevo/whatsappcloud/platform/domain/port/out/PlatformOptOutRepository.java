package com.keevo.whatsappcloud.platform.domain.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** Story 21.6 — the numbers (digits) that answered STOP to the platform number. */
public interface PlatformOptOutRepository {

    /** STOP: recorded once (an earlier STOP keeps its date); returns whether the number was added. */
    boolean optOut(String phone, Instant at);

    /** START: removed; returns whether the number was on the list. */
    boolean optIn(String phone);

    /** The numbers among {@code phones} on the list. One query. */
    Set<String> optedOut(Collection<String> phones);
}
