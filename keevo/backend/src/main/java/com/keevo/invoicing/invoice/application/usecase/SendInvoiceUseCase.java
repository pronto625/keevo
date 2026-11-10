package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.application.service.InvoiceRecipients;
import com.keevo.invoicing.invoice.application.service.InvoiceSendRateLimiter;
import com.keevo.invoicing.invoice.application.service.channel.InvoiceSendChannel;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoiceSendRequest;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.invoicing.invoice.domain.model.SendStatus;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePresentationRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceSendRequestRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.capability.business.CompanyIdentity;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.InvoiceSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sends a document to its client with a signed public link (valid for the tenant's lifetime, 30 days by default):
 * Story 14.2 by WhatsApp; Story 20.7 a quote waiting for its validation too; Story 20.8 (FUS-FR22) by SMS or email
 * as well, one or several channels per call (Bref's {@code channels}), WhatsApp when none is named.
 *
 * <p>Template Method over the channel strategies ({@link InvoiceSendChannel}, exactly one per {@link SendChannel},
 * checked at startup), per channel:
 * <ol>
 *   <li>the document must be sendable (issued / paid invoice, quote waiting; else 409);</li>
 *   <li>the channel resolves its recipient (the one typed, else the client's; missing / malformed → 422) and must be
 *       configured (503); the link needs an absolute public base ({@code PUBLIC_BASE_URL_MISSING} 503);</li>
 *   <li>at most 30 sendings per tenant per hour, all channels (429);</li>
 *   <li>the message leaves with no transaction open — SMS and email are counted on the tenant quota
 *       ({@code SMS} / {@code EMAILS}, 19.8/19.9) only after a provider accepted them. A failure ({@code QUOTA_EXCEEDED}
 *       403 before sending, 502 certainly not sent) records nothing on the document and gives the rate-limit slot
 *       back; an uncertain outcome ({@code INVOICE_SEND_UNCERTAIN}, maybe delivered) is never retried;</li>
 *   <li>once delivered, in one short transaction with the row locked, the sending is recorded and
 *       {@link InvoiceSentEvent} published (audit, with the channel). A recording failure after delivery never
 *       surfaces as a retryable error: {@code INVOICE_SENT_UNRECORDED} is logged and the sending stands.</li>
 * </ol>
 * Every attempt is logged in {@code invoice_send_requests} (online: one row with its final status).
 *
 * <p>Offline ({@code SEND_INVOICE}, {@link #enqueue}): the push transaction only validates and queues one PENDING row
 * per channel keyed on the operation id (a pushed again operation queues nothing); the send happens after commit
 * ({@code InvoiceSendOutbox}: after-commit trigger + ShedLock poller), through {@link #deliverQueued}.
 *
 * <p>Cost per channel: invoice + presentation + preferences by PK (+ settings for a quote's seller name), in-memory
 * rate limit, provider call(s), then 1 locked PK read + 1 UPDATE + 1 audit insert, + 1 request-log insert; SMS /
 * email add the two short quota transactions of {@code TenantMessaging}; email adds the PDF render.
 */
@Service
public class SendInvoiceUseCase {

    /** Path of the public invoice link (JWT-free, see {@code SecurityConfig.PUBLIC_PATHS}). */
    public static final String SHARED_PATH = InvoicePublicLinkUseCase.SHARED_PATH;

    private static final Logger log = LoggerFactory.getLogger(SendInvoiceUseCase.class);

    /**
     * Story 20.8 — what to send: the channels (none = WhatsApp, the 14.2 behaviour), the number typed (WhatsApp /
     * SMS) and the address typed (email), {@code null} / blank = the client's.
     */
    public record SendRequest(List<SendChannel> channels, String phone, String email) {

        public SendRequest {
            LinkedHashSet<SendChannel> distinct = new LinkedHashSet<>();
            if (channels != null) {
                channels.stream().filter(java.util.Objects::nonNull).forEach(distinct::add);
            }
            if (distinct.isEmpty()) {
                distinct.add(SendChannel.WHATSAPP);
            }
            channels = List.copyOf(distinct);
        }

        /** One channel ({@code null} = WhatsApp). */
        public SendRequest(SendChannel channel, String phone, String email) {
            this(channel == null ? null : List.of(channel), phone, email);
        }

        public static SendRequest whatsApp(String phone) {
            return new SendRequest(SendChannel.WHATSAPP, phone, null);
        }

        /** The override of {@code channel}: the address for email, the number otherwise. */
        public String overrideFor(SendChannel channel) {
            return channel == SendChannel.EMAIL ? email : phone;
        }
    }

    /**
     * The result of one channel: SENT (with the recorded document), FAILED (certainly not sent) or UNKNOWN (maybe
     * delivered); {@code error} = the refusal, {@code null} when sent.
     */
    public record ChannelResult(SendChannel channel, SendStatus status, DomainException error, Invoice invoice) {

        public String errorCode() {
            return error == null ? null : error.getErrorCode().name();
        }

        /** Worth another automatic attempt (outbox): a provider or configuration hiccup, never a refusal. */
        public boolean retryable() {
            return status == SendStatus.FAILED && error != null && RETRYABLE.contains(error.getErrorCode());
        }
    }

    /** Every channel's result, and the document as it stands after them. */
    public record Results(Invoice invoice, List<ChannelResult> results) {
    }

    /** Failures worth retrying from the outbox (Bref retries any failure; Keevo never retries a refusal). */
    static final Set<ErrorCode> RETRYABLE = Set.of(ErrorCode.WHATSAPP_DELIVERY_FAILED, ErrorCode.SMS_DELIVERY_FAILED,
            ErrorCode.EMAIL_DELIVERY_FAILED, ErrorCode.WHATSAPP_UNAVAILABLE, ErrorCode.SMS_UNAVAILABLE,
            ErrorCode.EMAIL_UNAVAILABLE, ErrorCode.RATE_LIMIT_EXCEEDED, ErrorCode.INTERNAL_ERROR);

    private final InvoiceRepository invoices;
    private final InvoiceSendRequestRepository requests;
    private final Map<SendChannel, InvoiceSendChannel> channels = new EnumMap<>(SendChannel.class);
    private final InvoicePublicLinkUseCase links;
    private final InvoiceSendRateLimiter rateLimiter;
    private final CompanyIdentity companyIdentity;
    private final InvoicingSettingsRepository settings;
    private final InvoicePresentationRepository presentations;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final TransactionTemplate outsideTransaction;
    private final Clock clock;

    public SendInvoiceUseCase(InvoiceRepository invoices, InvoiceSendRequestRepository requests,
                              List<InvoiceSendChannel> channels, InvoicePublicLinkUseCase links,
                              InvoiceSendRateLimiter rateLimiter, CompanyIdentity companyIdentity,
                              ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                              Clock clock, InvoicingSettingsRepository settings,
                              InvoicePresentationRepository presentations) {
        this.invoices = invoices;
        this.requests = requests;
        for (InvoiceSendChannel channel : channels) {
            if (this.channels.put(channel.channel(), channel) != null) {
                throw new IllegalStateException("Two senders for channel " + channel.channel());
            }
        }
        if (this.channels.size() != SendChannel.values().length) {
            throw new IllegalStateException("A sender is required for every channel, found " + this.channels.keySet());
        }
        this.links = links;
        this.rateLimiter = rateLimiter;
        this.companyIdentity = companyIdentity;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.outsideTransaction = new TransactionTemplate(transactionManager);
        this.outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.clock = clock;
        this.settings = settings;
        this.presentations = presentations;
    }

    /**
     * Story 14.2 shape: by WhatsApp.
     *
     * @param phone   WhatsApp number typed by the user, {@code null} / blank = the client's phone
     * @param baseUrl absolute base of the public link ({@code https://keevo.ad2s.cm}), without trailing slash
     * @return the invoice with its sending recorded
     */
    public Invoice send(UUID id, String phone, String baseUrl, UUID actorId, String tenantSchema) {
        return send(id, SendRequest.whatsApp(phone), baseUrl, actorId, tenantSchema);
    }

    /**
     * One channel (the first of {@code request}), online: the refusal is thrown.
     *
     * @return the document with its sending recorded
     */
    public Invoice send(UUID id, SendRequest request, String baseUrl, UUID actorId, String tenantSchema) {
        SendRequest one = new SendRequest(request.channels().get(0), request.phone(), request.email());
        ChannelResult result = sendAll(id, one, baseUrl, actorId, tenantSchema).results().get(0);
        if (result.error() != null) {
            throw result.error();
        }
        return result.invoice();
    }

    /**
     * Every channel of {@code request}, online, each independently (one failing never stops the next); every attempt
     * logged. Runs with any caller transaction suspended.
     */
    public Results sendAll(UUID id, SendRequest request, String baseUrl, UUID actorId, String tenantSchema) {
        return outsideTransaction.execute(status -> {
            List<ChannelResult> results = new ArrayList<>();
            Invoice last = null;
            for (SendChannel channel : request.channels()) {
                String override = request.overrideFor(channel);
                ChannelResult result = attempt(id, channel, override, baseUrl, actorId, tenantSchema);
                log(id, channel, override, result, actorId);
                results.add(result);
                if (result.invoice() != null) {
                    last = result.invoice();
                }
            }
            if (last == null) {
                last = invoices.findById(id).orElse(null);
            }
            return new Results(last, results);
        });
    }

    /**
     * Story 20.8 — offline {@code SEND_INVOICE}, in the push's per-operation transaction: validates (sendable,
     * recipient of every channel — same French refusals) and queues one PENDING request per channel keyed on
     * {@code operationId}. Nothing is sent here. A pushed again operation queues nothing (unique key).
     *
     * @return the requests inserted now (empty when the operation was already queued) and the document
     */
    public Queued enqueue(UUID id, SendRequest request, String operationId, UUID actorId, String tenantSchema) {
        Invoice invoice = invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
        invoice.assertSendable();
        for (SendChannel channel : request.channels()) {
            channels.get(channel).recipient(invoice, request.overrideFor(channel));
        }
        Instant now = clock.instant();
        List<InvoiceSendRequest> inserted = new ArrayList<>();
        for (SendChannel channel : request.channels()) {
            InvoiceSendRequest queued = InvoiceSendRequest.queued(id, operationId, channel,
                    blankToNull(request.overrideFor(channel)), actorId, now);
            if (requests.insertIfAbsent(queued)) {
                inserted.add(queued);
            }
        }
        if (!inserted.isEmpty()) {
            invoices.markSendStatus(id, SendStatus.PENDING, inserted.get(inserted.size() - 1).channel(), null, now);
        }
        return new Queued(invoice, inserted);
    }

    /** The requests queued by {@link #enqueue} and the document. */
    public record Queued(Invoice invoice, List<InvoiceSendRequest> inserted) {
    }

    /**
     * Story 20.8 — the outbox's delivery of a claimed (SENDING) request, with no transaction around the provider call.
     * The request row and the document's last send status are updated by the caller ({@code InvoiceSendOutbox}).
     *
     * @param baseUrl the configured public base ({@code keevo.public-base-url}); blank → {@code PUBLIC_BASE_URL_MISSING}
     */
    public ChannelResult deliverQueued(InvoiceSendRequest request, String baseUrl, String tenantSchema) {
        return outsideTransaction.execute(status -> attempt(request.invoiceId(), request.channel(),
                request.recipientOverride(), baseUrl, request.createdBy(), tenantSchema));
    }

    /** One channel: check, deliver, record. A refusal is returned (never thrown); a programming error propagates. */
    private ChannelResult attempt(UUID id, SendChannel channelCode, String override, String baseUrl, UUID actorId,
                                  String tenantSchema) {
        InvoiceSendChannel channel = channels.get(channelCode);
        boolean slot = false;
        Invoice invoice;
        String reference;
        String to;
        try {
            invoice = invoices.findById(id).orElseThrow(InvoiceQueryUseCase::notFound);
            invoice.assertSendable();
            to = channel.recipient(invoice, override);
            channel.assertAvailable(tenantSchema);
            String base = absoluteBase(baseUrl, tenantSchema);
            rateLimiter.acquire(tenantSchema);
            slot = true;
            InvoicePresentation presentation = presentations.find(id).orElse(InvoicePresentation.NONE);
            // Story 20.7 — an imported invoice without Keevo number is sent under its Bref reference
            reference = InvoicePublicLinkUseCase.reference(invoice, presentation.legacyReference());
            InvoicePublicLinkUseCase.Link link = links.signedLink(invoice.id(), presentation.linkVersion(), base,
                    tenantSchema);
            Seller seller = seller(invoice, tenantSchema);
            channel.deliver(new InvoiceSendChannel.Message(invoice, reference, seller.name(), seller.address(),
                    seller.phone(), link.url(), link.validityDays(), to, tenantSchema));
        } catch (DomainException e) {
            boolean uncertain = e.getErrorCode() == ErrorCode.INVOICE_SEND_UNCERTAIN;
            if (slot && !uncertain) {
                rateLimiter.release(tenantSchema); // maybe delivered: the slot stays used
            }
            log.warn("INVOICE_SEND_FAILED channel={} tenant={} code={}{}", channelCode, tenantSchema,
                    e.getErrorCode(), e.getCause() == null ? "" : " cause=" + e.getCause().getClass().getSimpleName());
            return new ChannelResult(channelCode, uncertain ? SendStatus.UNKNOWN : SendStatus.FAILED, e, null);
        } catch (RuntimeException e) {
            if (slot) {
                rateLimiter.release(tenantSchema);
            }
            throw e;
        }

        // Delivered: always recorded (even if the invoice was cancelled meanwhile — the client has the message).
        Instant now = clock.instant();
        Invoice recorded;
        try {
            recorded = tx.execute(status -> {
                Invoice locked = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
                Invoice withSending = locked.recordSending(to, channelCode, now);
                invoices.markSent(withSending);
                events.publishEvent(new InvoiceSentEvent(withSending.id(), reference, withSending.client().name(),
                        withSending.totals().totalTtc(), InvoiceRecipients.mask(to), actorId, tenantSchema, now,
                        withSending.isQuote(), channelCode.name()));
                return withSending;
            });
        } catch (RuntimeException e) {
            // the client has the message: never a retryable error (a retry would send it twice)
            log.error("INVOICE_SENT_UNRECORDED channel={} number={} tenant={} error={}", channelCode, reference,
                    tenantSchema, e.getClass().getSimpleName());
            recorded = invoice.recordSending(to, channelCode, now);
        }
        log.info("INVOICE_SENT channel={} number={} tenant={}", channelCode, reference, tenantSchema);
        return new ChannelResult(channelCode, SendStatus.SENT, null, recorded);
    }

    /** Online attempt logged (best effort: the log never changes the answer). */
    private void log(UUID id, SendChannel channel, String override, ChannelResult result, UUID actorId) {
        try {
            tx.executeWithoutResult(status -> requests.insert(InvoiceSendRequest.attempted(id, channel,
                    blankToNull(override), result.status(), result.errorCode(), actorId, clock.instant())));
        } catch (RuntimeException e) {
            log.warn("INVOICE_SEND_LOG_FAILED channel={} status={} error={}", channel, result.status(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * The absolute base of the link: a link sent outside the app must open from the client's phone. Not absolute
     * (no request, {@code keevo.public-base-url} unset) → {@code PUBLIC_BASE_URL_MISSING}, nothing sent.
     */
    private static String absoluteBase(String baseUrl, String tenantSchema) {
        String base = InvoicePublicLinkUseCase.stripTrailingSlash(baseUrl == null ? "" : baseUrl.strip());
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            log.error("INVOICE_SEND_NO_PUBLIC_BASE tenant={} — set KEEVO_PUBLIC_BASE_URL", tenantSchema);
            throw new DomainException(ErrorCode.PUBLIC_BASE_URL_MISSING, "No absolute public base URL");
        }
        return base;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The seller as printed on the PDF (name, address, phone). */
    private record Seller(String name, String address, String phone) {
    }

    /**
     * The seller printed on the PDF: the snapshot frozen at issue; Story 20.7 — for a quote (no snapshot) the current
     * legal name of the settings, as on its PDF; else the company display name.
     */
    private Seller seller(Invoice invoice, String tenantSchema) {
        if (invoice.seller() != null) {
            String name = invoice.seller().legalName();
            return new Seller(name != null && !name.isBlank() ? name : companyIdentity.displayName(tenantSchema),
                    invoice.seller().address(), invoice.seller().phone());
        }
        InvoicingSettings current = settings.find().orElse(null);
        if (current != null && current.legalName() != null && !current.legalName().isBlank()) {
            return new Seller(current.legalName(), current.address(), current.phone());
        }
        return new Seller(companyIdentity.displayName(tenantSchema),
                current == null ? null : current.address(), current == null ? null : current.phone());
    }
}
