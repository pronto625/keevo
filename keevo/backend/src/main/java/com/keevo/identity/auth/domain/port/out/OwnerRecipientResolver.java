package com.keevo.identity.auth.domain.port.out;

import java.util.Optional;

/**
 * OwnerRecipientResolver — single rule for picking the WhatsApp recipient of owner-directed
 * messages (EOD / weekly / combined reports, resend, retry, test report, day closure,
 * stock / product / sale notifications) — v1s-14-17.
 *
 * <p>Rule:
 * <ul>
 *   <li>recipient = the tenant's primary active owner (oldest active OWNER membership of an
 *       active user, tie-break user id) — see {@link UserRepository#findOwnerByTenantSchemaName};</li>
 *   <li>the phone is normalised with {@code PhoneNumbers.normalize} and accepted only when it
 *       matches {@code ^\+\d+$};</li>
 *   <li>no fallback to another owner or to any configured number;</li>
 *   <li>a null / blank tenant yields empty without any lookup;</li>
 *   <li>repository exceptions propagate to the caller.</li>
 * </ul>
 *
 * <p>Empty means "no WhatsApp recipient": each caller keeps its own no-recipient behaviour.
 */
public interface OwnerRecipientResolver {

    /**
     * @param tenantSchemaName tenant schema name (e.g. "kv_abc123")
     * @return the primary active owner's phone in canonical {@code +<digits>} form, or empty
     */
    Optional<String> resolveOwnerWhatsAppPhone(String tenantSchemaName);
}
