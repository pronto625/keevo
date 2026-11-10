package com.keevo.assistant.chat.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantExchange.Message;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolCall;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolResult;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolSpec;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import com.keevo.shared.ai.AiCredits;
import com.keevo.shared.ai.AiToolChannel;
import com.keevo.shared.ai.AiToolDescriptor;
import com.keevo.shared.ai.AiToolRegistry;
import com.keevo.shared.ai.AiToolResult;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.capability.entitlement.UsageStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Story 21.4 — answers a free-text question (Bref Point's {@code POST /v1/ai/chat}) with a model that calls the AI
 * tools of 21.8 — never SQL.
 *
 * <p><b>Pattern:</b> bounded agent loop (the model asks for tools, the service runs them through
 * {@link AiToolRegistry} on the calling thread — the HTTP request's, so the session's actor and tenant are the
 * request's and every REST gate applies — and hands the results back) with hard caps on rounds, calls and the text
 * fed to the model; the provider is a port (strategy). One question at a time per account — the user in their company,
 * like Bref (21.4.2: per company before) — by an in-memory guard (the application runs as one instance like Bref's
 * API); members of one company may ask at the same time, at most {@value #MAX_CONCURRENT_QUESTIONS} questions run at
 * once on the instance, and an account whose questions failed {@value #MAX_FAILED_PER_HOUR} times in an hour waits
 * (failed questions cost the provider but no credit). The deadline ({@link #MAX_DURATION}) bounds every provider call:
 * the time left is handed to the adapter.
 *
 * <p><b>Quota (19.8):</b> one {@code AI_CREDITS} unit per answered question, through {@link AiCredits}: reserved before
 * the model is called (exact refusal, counting the questions and MCP calls already in flight in the company), consumed
 * after the answer, released in any case. A disabled provider, a refused quota, a provider failure, a tool failure, a
 * loop over its limits or a concurrent question count nothing.
 *
 * <p><b>Cost:</b> 1 rolled-back quota transaction, at most {@value #MAX_ROUNDS} provider calls and
 * {@value #MAX_TOOL_CALLS} tool calls (each a bounded use case of 21.8 + one audit row), 1 quota transaction and 1
 * usage read. Logs carry outcome, rounds, tool count and duration — never the question, the answer or a name.
 */
@Service
public class AssistantChatService {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);

    public static final int MAX_MESSAGE_CHARS = 2_000;
    public static final int MAX_HISTORY = 20;
    static final int MAX_HISTORY_CHARS = 12_000;
    static final int MAX_ROUNDS = 4;
    static final int MAX_TOOL_CALLS = 8;
    static final int MAX_TOOL_RESULT_CHARS = 6_000;
    static final int MAX_TOOL_RESULTS_CHARS = 24_000;
    static final int MAX_REPLY_CHARS = 4_000;
    static final int MAX_OUTPUT_TOKENS = 800;
    /** Whole question, provider calls and tools included: no provider call waits beyond it. */
    static final Duration MAX_DURATION = Duration.ofSeconds(90);
    /** Questions answered at once on the instance (each holds a request thread up to {@link #MAX_DURATION}). */
    static final int MAX_CONCURRENT_QUESTIONS = 32;
    /** Failed questions per account in an hour before 429 (failures cost the provider, never a credit). */
    static final int MAX_FAILED_PER_HOUR = 20;
    static final String TOO_LARGE = "{\"erreur\":\"Résultat trop volumineux : demande moins de lignes ou une période "
            + "plus courte.\"}";
    static final String BUDGET_SPENT = "{\"erreur\":\"Assez de données pour cette question : réponds avec les "
            + "résultats déjà obtenus.\"}";

    /** A UUID, with or without its hyphens. */
    static final String UUID_REGEX = "(?<![0-9a-fA-F])[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}"
            + "-?[0-9a-fA-F]{12}(?![0-9a-fA-F])";
    /** An identifier alone between parentheses: removed with them. */
    static final Pattern UUID_IN_PARENS = Pattern.compile("\\s*\\(\\s*" + UUID_REGEX + "\\s*\\)");
    static final Pattern UUID_TEXT = Pattern.compile(UUID_REGEX);
    private static final Pattern OPEN_PAREN_SPACE = Pattern.compile("\\(\\s+");
    private static final Pattern SPACE_CLOSE_PAREN = Pattern.compile("\\s+\\)");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SPACE_BEFORE_POINT = Pattern.compile(" +([.,])");
    /** Markdown image: never rendered (an image URL would send data to a third party without a click). */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    /** Markdown link: its text is kept, its target dropped. */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+|\\bwww\\.\\S+");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    /** A message of the conversation history sent by the app (Bref: user / assistant only). */
    public record HistoryEntry(String role, String content) {
    }

    /**
     * @param reply            the answer, in French, without any identifier
     * @param creditsRemaining AI credits still usable now (the tightest of the day and month quotas, month packs
     *                         included); {@code null} when unlimited
     */
    public record Answer(String reply, Long creditsRemaining) {
    }

    private final AssistantModel model;
    private final AiToolRegistry tools;
    private final AiCredits credits;
    private final UsageQuotaPort quota;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Semaphore running = new Semaphore(MAX_CONCURRENT_QUESTIONS);
    /** Failed questions per account, fixed one-hour window from the first failure; bounded. */
    private final Cache<String, AtomicInteger> failures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1)).maximumSize(100_000).build();

    @Autowired
    public AssistantChatService(AssistantModel model, AiToolRegistry tools, AiCredits credits, UsageQuotaPort quota,
                                ObjectMapper mapper, Clock clock) {
        this(model, tools, credits, quota, mapper, clock, System::nanoTime);
    }

    /** Test seam: the monotonic time source of the deadline. */
    AssistantChatService(AssistantModel model, AiToolRegistry tools, AiCredits credits, UsageQuotaPort quota,
                         ObjectMapper mapper, Clock clock, LongSupplier nanoTime) {
        this.model = model;
        this.tools = tools;
        this.credits = credits;
        this.quota = quota;
        this.mapper = mapper;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    /** Whether free-text questions are possible at all (a provider is configured). */
    public boolean available() {
        return model.available();
    }

    /**
     * Answers {@code question} for the session's actor {@code userId} in company {@code tenantSchema}.
     *
     * @param userId the asking user (one question at a time each)
     * @param role   the actor's role (prompt context only)
     * @throws DomainException {@code AI_UNAVAILABLE}, {@code AI_QUESTION_IN_PROGRESS}, {@code RATE_LIMIT_EXCEEDED},
     *                         {@code QUOTA_EXCEEDED}, {@code AI_PROVIDER_ERROR}, {@code AI_ANSWER_FAILED},
     *                         {@code VALIDATION_ERROR}
     */
    public Answer ask(String tenantSchema, UUID userId, String role, String question, List<HistoryEntry> history) {
        String text = question == null ? "" : question.strip();
        if (text.isEmpty() || text.length() > MAX_MESSAGE_CHARS) {
            throw new InvalidRequestParameterException("message", "message blank or too long",
                    "La question doit contenir de 1 à " + MAX_MESSAGE_CHARS + " caractères");
        }
        if (!model.available()) {
            throw new DomainException(ErrorCode.AI_UNAVAILABLE, "no AI provider configured");
        }
        String account = tenantSchema + '/' + userId;
        AtomicInteger failed = failures.getIfPresent(account);
        if (failed != null && failed.get() >= MAX_FAILED_PER_HOUR) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "too many failed questions for this user");
        }
        if (!inFlight.add(account)) {
            throw new DomainException(ErrorCode.AI_QUESTION_IN_PROGRESS, "a question of this user is in flight");
        }
        long started = nanoTime.getAsLong();
        int[] counters = new int[2]; // rounds, tool calls
        String outcome = "ERROR";
        boolean slot = false;
        try {
            slot = running.tryAcquire();
            if (!slot) {
                throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "too many questions in flight");
            }
            try (AiCredits.Reservation credit = credits.reserve(tenantSchema)) {
                String reply = converse(text, history, role, counters, started);
                Long remaining = credit.consume() ? remaining(tenantSchema) : Long.valueOf(0L);
                outcome = "OK";
                return new Answer(reply, remaining);
            }
        } catch (DomainException e) {
            outcome = e.getDomainCode();
            throw e;
        } finally {
            if (slot) {
                running.release();
            }
            inFlight.remove(account);
            if (!"OK".equals(outcome) && counters[0] > 0) {
                failures.get(account, k -> new AtomicInteger()).incrementAndGet();
            }
            log.info("[AI] question {} rounds={} tools={} durationMs={}", outcome, counters[0], counters[1],
                    (nanoTime.getAsLong() - started) / 1_000_000);
        }
    }

    // ── loop ────────────────────────────────────────────────────────────────────────────────────────────

    private String converse(String question, List<HistoryEntry> history, String role, int[] counters, long started) {
        List<ToolSpec> specs = new ArrayList<>();
        for (AiToolDescriptor d : tools.allowedTools()) {
            specs.add(new ToolSpec(d.name(), d.description(), d.inputSchema()));
        }
        List<Message> messages = new ArrayList<>(history(history));
        messages.add(Message.user(question));
        String system = AssistantPrompt.system(LocalDate.now(clock.withZone(KeevoZones.WAT)),
                AssistantPrompt.roleLabel(role));
        int budget = MAX_TOOL_RESULTS_CHARS;
        boolean answerNow = false;
        long deadline = started + MAX_DURATION.toNanos();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean last = answerNow || round == MAX_ROUNDS - 1;
            long left = deadline - nanoTime.getAsLong();
            if (left <= 0) {
                throw new DomainException(ErrorCode.AI_PROVIDER_ERROR, "question deadline reached");
            }
            AssistantTurn turn;
            try {
                counters[0]++;
                // the same tools every round (a conversation holding tool calls keeps them); the last answers only
                turn = model.respond(new AssistantExchange(system, messages, specs, MAX_OUTPUT_TOKENS, last,
                        Duration.ofNanos(left)));
            } catch (AssistantModelException e) {
                log.warn("[AI] provider failure: {}", e.getMessage());
                throw new DomainException(e.unusableAnswer() ? ErrorCode.AI_ANSWER_FAILED : ErrorCode.AI_PROVIDER_ERROR,
                        e.unusableAnswer() ? "unusable model answer" : "AI provider failed");
            }
            if (!turn.wantsTools()) {
                String reply = sanitize(turn.text());
                if (reply.isEmpty()) {
                    throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "empty answer");
                }
                return reply;
            }
            if (last) {
                throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "tools asked on the answer-only round");
            }
            if (counters[1] + turn.toolCalls().size() > MAX_TOOL_CALLS) {
                answerNow = true; // over the call limit: nothing run, the model answers from what it has
                continue;
            }
            messages.add(Message.toolCalls(turn.text(), turn.toolCalls()));
            List<ToolResult> results = new ArrayList<>(turn.toolCalls().size());
            for (ToolCall call : turn.toolCalls()) {
                String content;
                if (budget <= 0) {
                    content = BUDGET_SPENT; // not run: enough data was fed to the model
                } else {
                    counters[1]++;
                    AiToolResult r = tools.call(call.name(), call.arguments(), AiToolChannel.ASSISTANT);
                    if (r.outcome() == AiToolResult.Outcome.ERROR) {
                        throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "tool " + call.name() + " failed");
                    }
                    content = r.isOk() ? (r.json() == null ? "{}" : r.json()) : refusal(r.message());
                    if (content.length() > Math.min(MAX_TOOL_RESULT_CHARS, budget)) {
                        content = TOO_LARGE; // never a cut (invalid) JSON
                    }
                    budget -= content.length();
                }
                results.add(new ToolResult(call.id(), call.name(), content));
            }
            messages.add(Message.toolResults(results));
            if (budget <= 0) {
                answerNow = true;
            }
        }
        throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "round limit reached");
    }

    /** Bref's history: user / assistant only (never a system message), the 20 last, at most 12 000 characters. */
    static List<Message> history(List<HistoryEntry> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        Deque<Message> kept = new ArrayDeque<>();
        int chars = 0;
        List<HistoryEntry> recent = history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            HistoryEntry h = recent.get(i);
            if (h == null || h.content() == null || h.content().isBlank()) {
                continue;
            }
            String content = h.content().length() > MAX_MESSAGE_CHARS ? h.content().substring(0, MAX_MESSAGE_CHARS)
                    : h.content();
            if (chars + content.length() > MAX_HISTORY_CHARS) {
                break;
            }
            if ("user".equals(h.role())) {
                kept.addFirst(Message.user(content));
            } else if ("assistant".equals(h.role())) {
                kept.addFirst(Message.assistant(content));
            } else {
                continue;
            }
            chars += content.length();
        }
        // a conversation starts with a question: an answer left first by the character bound is dropped
        while (!kept.isEmpty() && kept.peekFirst().role() != AssistantExchange.Role.USER) {
            kept.removeFirst();
        }
        return List.copyOf(kept);
    }

    private String refusal(String message) {
        try {
            return mapper.writeValueAsString(Map.of("erreur", message == null ? "Outil indisponible" : message));
        } catch (Exception e) {
            return "{\"erreur\":\"Outil indisponible\"}";
        }
    }

    /**
     * No identifier ever reaches the app: every UUID (with or without hyphens) is removed; no image, link target, URL
     * or HTML tag either (a reply is plain French text with bold — an injected instruction in a client or product name
     * cannot make the app fetch a URL carrying data); bounded length, never cut inside a character.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = MD_LINK.matcher(MD_IMAGE.matcher(text).replaceAll("")).replaceAll("$1");
        clean = HTML_TAG.matcher(URL.matcher(clean).replaceAll("")).replaceAll("");
        clean = UUID_TEXT.matcher(UUID_IN_PARENS.matcher(clean).replaceAll("")).replaceAll("");
        clean = SPACE_CLOSE_PAREN.matcher(OPEN_PAREN_SPACE.matcher(clean).replaceAll("(")).replaceAll(")")
                .replace("()", "");
        clean = SPACE_BEFORE_POINT.matcher(SPACES.matcher(clean).replaceAll(" ")).replaceAll("$1").strip();
        if (clean.length() <= MAX_REPLY_CHARS) {
            return clean;
        }
        int end = Character.isHighSurrogate(clean.charAt(MAX_REPLY_CHARS - 1)) ? MAX_REPLY_CHARS - 1 : MAX_REPLY_CHARS;
        return clean.substring(0, end).strip() + " …";
    }

    // ── quota ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Whether the company may still ask a question now (the reservation check of {@link AiCredits}, nothing kept);
     * also gates the model titles of new conversations.
     */
    public boolean hasCredit(String tenantSchema) {
        return credits.available(tenantSchema);
    }

    /** Credits left after an answer; {@code null} when unlimited or unreadable (the answer is delivered anyway). */
    private Long remaining(String tenantSchema) {
        try {
            return remaining(quota.statusForSchema(tenantSchema));
        } catch (RuntimeException e) {
            log.warn("[AI] usage read after an answer failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The tightest of the DAY and MONTH quotas (month packs included); {@code null} when neither limits. */
    static Long remaining(UsageStatus status) {
        Long result = null;
        long packs = status.credits().getOrDefault(UsageMetric.AI_CREDITS, 0L);
        for (UsageStatus.QuotaUsage q : status.quotas()) {
            if (q.metric() != UsageMetric.AI_CREDITS || q.remaining() == null) {
                continue;
            }
            long left = q.period() == QuotaPeriod.DAY ? q.remaining() : q.remaining() + packs;
            result = result == null ? left : Math.min(result, left);
        }
        return result;
    }
}
