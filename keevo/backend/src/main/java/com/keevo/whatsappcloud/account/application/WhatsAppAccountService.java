package com.keevo.whatsappcloud.account.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.auth.WhatsAppAccountChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.infrastructure.security.crypto.SecretCipher;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.whatsappcloud.account.domain.model.RoutedAccount;
import com.keevo.whatsappcloud.account.domain.model.WhatsAppAccount;
import com.keevo.whatsappcloud.account.domain.port.out.WhatsAppAccountRepository;
import com.keevo.whatsappcloud.config.MetaErrors;
import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.PhoneNumberInfo;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 21.5 — the company's WhatsApp Business account: its status (every member), the connection from Meta's embedded
 * signup and the disconnection (owner), and the decrypted credentials the other services use for one Graph call.
 *
 * <p>Connect: refused at once when Graph is disabled or no encryption key is configured (503), or when the number is
 * connected to another company (409, checked before any Meta call); then the code is exchanged for the business token,
 * the number is read from the WABA (it must belong to it), the Keevo app is subscribed to the WABA's webhooks, and the
 * row is written with the token encrypted (AES-256-GCM bound to the company id). A Meta failure writes nothing (502).
 * Connecting again replaces the account (one per company). Cost: 1 indexed lookup, 3 Graph calls, 1 upsert, 1 audit
 * row.
 */
@Service
public class WhatsAppAccountService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppAccountService.class);

    /** Meta ids are numeric strings. */
    static final String META_ID = "^[0-9]{1,64}$";

    /** What the app reads: connected or not, and the number as Meta displays it. */
    public record Status(boolean connected, String displayPhoneNumber, String verifiedName, Instant connectedAt) {
        static final Status NOT_CONNECTED = new Status(false, null, null, null);
    }

    /**
     * A connection: the status, and whether the number now belongs to another WABA than the company's previous one
     * (its templates must then be submitted again).
     */
    public record Connection(Status status, boolean otherWaba) {
    }

    /** One Graph call's identity: the WABA, the number and the clear token (memory only, never logged). */
    public record Credentials(String wabaId, String phoneNumberId, String token) {
        @Override
        public String toString() {
            return "Credentials[waba=" + wabaId + ", phoneNumberId=" + phoneNumberId + "]";
        }
    }

    private final WhatsAppAccountRepository accounts;
    private final MetaGraphClient graph;
    private final SecretCipher cipher;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WhatsAppAccountService(WhatsAppAccountRepository accounts, MetaGraphClient graph, SecretCipher cipher,
                                  ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                  JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.graph = graph;
        this.cipher = cipher;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Status status(String schema) {
        return accounts.find(schema)
                .map(a -> new Status(true, a.displayPhoneNumber(), a.verifiedName(), a.connectedAt()))
                .orElse(Status.NOT_CONNECTED);
    }

    /**
     * Connects the company's number (embedded signup result).
     *
     * @throws InvalidRequestParameterException 400 code / ids missing or malformed
     * @throws DomainException {@code WHATSAPP_CLOUD_UNAVAILABLE} 503, {@code WHATSAPP_NUMBER_ALREADY_CONNECTED} 409,
     *                         {@code WHATSAPP_CLOUD_META_ERROR} 502
     */
    public Connection connect(String schema, UUID actorId, String code, String wabaId, String phoneNumberId) {
        if (!graph.isEnabled() || !cipher.isAvailable()) {
            throw new DomainException(ErrorCode.WHATSAPP_CLOUD_UNAVAILABLE, "WhatsApp Cloud not configured");
        }
        if (code == null || code.isBlank() || code.length() > 2048) {
            throw new InvalidRequestParameterException("code", "missing",
                    "La connexion WhatsApp a échoué : recommencez depuis l'application");
        }
        String waba = metaId(wabaId, "wabaId");
        String number = metaId(phoneNumberId, "phoneNumberId");
        UUID tenantId = tenantId(schema);
        if (ownedByAnother(number, tenantId)) {
            throw new DomainException(ErrorCode.WHATSAPP_NUMBER_ALREADY_CONNECTED, "Number connected elsewhere");
        }
        String token;
        PhoneNumberInfo info;
        try {
            token = graph.exchangeCode(code.strip());
            info = graph.phoneNumber(waba, number, token);
            graph.subscribeApp(waba, token);
        } catch (MetaGraphException e) {
            log.warn("[WhatsApp Cloud] connection refused for tenant {} ({})", schema, e.kind());
            throw MetaErrors.of(e, false);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        WhatsAppAccount account = new WhatsAppAccount(tenantId, schema, waba, number,
                bounded(info.displayPhoneNumber(), 32), bounded(info.verifiedName(), 200),
                cipher.encrypt(token, tenantId.toString()), actorId, now);
        String previousWaba = accounts.find(schema).map(WhatsAppAccount::wabaId).orElse(null);
        // a number left behind by a deleted company never blocks it
        accounts.deleteOfDeletedTenants(number);
        // single statement, no surrounding transaction: a unique violation (race on the number) leaves nothing aborted
        if (!accounts.save(account)) {
            throw new DomainException(ErrorCode.WHATSAPP_NUMBER_ALREADY_CONNECTED, "Number connected elsewhere");
        }
        try {
            tx.executeWithoutResult(s -> events.publishEvent(new WhatsAppAccountChangedEvent(actorId, schema,
                    tenantId, account.displayPhoneNumber(), WhatsAppAccountChangedEvent.Change.CONNECTED, now)));
        } catch (RuntimeException e) {
            // the number is connected: an audit failure never turns it into an error for the owner
            log.error("[WhatsApp Cloud] connection of tenant {} not audited ({})", schema,
                    e.getClass().getSimpleName());
        }
        log.info("[WhatsApp Cloud] number connected for tenant {}", schema);
        return new Connection(new Status(true, account.displayPhoneNumber(), account.verifiedName(), now),
                previousWaba != null && !previousWaba.equals(waba));
    }

    /** Deletes the account (and its token); idempotent. The messages and templates stay. */
    public void disconnect(String schema, UUID actorId) {
        tx.executeWithoutResult(s -> accounts.delete(schema).ifPresent(a -> events.publishEvent(
                new WhatsAppAccountChangedEvent(actorId, schema, a.tenantId(), a.displayPhoneNumber(),
                        WhatsAppAccountChangedEvent.Change.DISCONNECTED, clock.instant()))));
    }

    /**
     * The company's decrypted credentials, or empty when not connected. A token that no longer opens (key rotated
     * without re-encryption, tampered row) reads as not connected and is logged without its value.
     */
    public Optional<Credentials> credentials(String schema) {
        return accounts.find(schema).flatMap(this::open);
    }

    private Optional<Credentials> open(WhatsAppAccount a) {
        if (!cipher.isAvailable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Credentials(a.wabaId(), a.phoneNumberId(),
                    cipher.decrypt(a.tokenEnc(), a.tenantId().toString())));
        } catch (IllegalStateException e) {
            log.error("[WhatsApp Cloud] access token of tenant {} does not open ({}): reconnect needed",
                    a.tenantSchema(), e.getMessage());
            return Optional.empty();
        }
    }

    private boolean ownedByAnother(String phoneNumberId, UUID tenantId) {
        List<RoutedAccount> owners = accounts.routeByPhoneNumberIds(List.of(phoneNumberId));
        return owners.stream().anyMatch(o -> !o.tenantId().equals(tenantId));
    }

    private UUID tenantId(String schema) {
        TenantSchema.validate(schema);
        return jdbc.queryForObject("SELECT id FROM public.tenants WHERE schema_name = ?", UUID.class, schema);
    }

    private static String metaId(String value, String field) {
        if (value == null || !value.strip().matches(META_ID)) {
            throw new InvalidRequestParameterException(field, "not a Meta id",
                    "La connexion WhatsApp a échoué : recommencez depuis l'application");
        }
        return value.strip();
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
