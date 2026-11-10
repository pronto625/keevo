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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * API); members of one company may ask at the same time.
 *
 * <p><b>Quota (19.8):</b> one {@code AI_CREDITS} unit per answered question. Checked before the model is called by
 * the very increment of 19.8 inside a transaction that is rolled back (exact refusal, nothing kept), consumed after
 * the answer in its own transaction. A disabled provider, a refused quota, a provider failure, a tool failure, a loop
 * over its limits or a concurrent question count nothing. Members of one company asking at once stay exact: each
 * consumption is one guarded upsert of 19.8 (never beyond the quota, never lost); when the last credit goes to another
 * member during a question, that answer is delivered uncounted (it is already paid to the provider) — at most one per
 * member asking at that moment.
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
    /** Whole question, provider calls and tools included: beyond, the next model call is not made. */
    static final java.time.Duration MAX_DURATION = java.time.Duration.ofSeconds(90);
    static final String TOO_LARGE = "{\"erreur\":\"Résultat trop volumineux : demande moins de lignes ou une période "
            + "plus courte.\"}";
    static final String BUDGET_SPENT = "{\"erreur\":\"Assez de données pour cette question : réponds avec les "
            + "résultats déjà obtenus.\"}";

    static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    /** An identifier alone between parentheses: removed with them. */
    static final Pattern UUID_IN_PARENS = Pattern.compile("\\s*\\(\\s*" + UUID_REGEX + "\\s*\\)");
    static final Pattern UUID_TEXT = Pattern.compile(UUID_REGEX);
    private static final Pattern OPEN_PAREN_SPACE = Pattern.compile("\\(\\s+");
    private static final Pattern SPACE_CLOSE_PAREN = Pattern.compile("\\s+\\)");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SPACE_BEFORE_POINT = Pattern.compile(" +([.,])");

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
    private final UsageQuotaPort quota;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AssistantChatService(AssistantModel model, AiToolRegistry tools, UsageQuotaPort quota,
                                TransactionTemplate tx, ObjectMapper mapper, Clock clock) {
        this.model = model;
        this.tools = tools;
        this.quota = quota;
        this.tx = tx;
        this.mapper = mapper;
        this.clock = clock;
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
     * @throws DomainException {@code AI_UNAVAILABLE}, {@code AI_QUESTION_IN_PROGRESS}, {@code QUOTA_EXCEEDED},
     *                         {@code AI_PROVIDER_ERROR}, {@code AI_ANSWER_FAILED}, {@code VALIDATION_ERROR}
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
        if (!inFlight.add(account)) {
            throw new DomainException(ErrorCode.AI_QUESTION_IN_PROGRESS, "a question of this user is in flight");
        }
        long started = System.nanoTime();
        int[] counters = new int[2]; // rounds, tool calls
        String outcome = "ERROR";
        try {
            checkQuota(tenantSchema);
            String reply = converse(text, history, role, counters);
            Long remaining = consume(tenantSchema);
            outcome = "OK";
            return new Answer(reply, remaining);
        } catch (DomainException e) {
            outcome = e.getDomainCode();
            throw e;
        } finally {
            inFlight.remove(account);
            log.info("[AI] question {} rounds={} tools={} durationMs={}", outcome, counters[0], counters[1],
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    // ── loop ────────────────────────────────────────────────────────────────────────────────────────────

    private String converse(String question, List<HistoryEntry> history, String role, int[] counters) {
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
        long deadline = System.nanoTime() + MAX_DURATION.toNanos();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean last = answerNow || round == MAX_ROUNDS - 1;
            if (round > 0 && System.nanoTime() > deadline) {
                throw new DomainException(ErrorCode.AI_PROVIDER_ERROR, "question deadline reached");
            }
            AssistantTurn turn;
            try {
                counters[0]++;
                turn = model.respond(new AssistantExchange(system, messages, last ? List.of() : specs,
                        MAX_OUTPUT_TOKENS));
            } catch (AssistantModelException e) {
                log.warn("[AI] provider failure: {}", e.getMessage());
                throw new DomainException(ErrorCode.AI_PROVIDER_ERROR, "AI provider failed");
            }
            if (!turn.wantsTools()) {
                String reply = sanitize(turn.text());
                if (reply.isEmpty()) {
                    throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "empty answer");
                }
                return reply;
            }
            if (last) {
                throw new DomainException(ErrorCode.AI_ANSWER_FAILED, "tools asked without tools offered");
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
                    content = r.isOk() ? r.json() : refusal(r.message());
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
        return List.copyOf(kept);
    }

    private String refusal(String message) {
        try {
            return mapper.writeValueAsString(Map.of("erreur", message == null ? "Outil indisponible" : message));
        } catch (Exception e) {
            return "{\"erreur\":\"Outil indisponible\"}";
        }
    }

    /** No identifier ever reaches the app: every UUID is removed; bounded length. */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = UUID_TEXT.matcher(UUID_IN_PARENS.matcher(text).replaceAll("")).replaceAll("");
        clean = SPACE_CLOSE_PAREN.matcher(OPEN_PAREN_SPACE.matcher(clean).replaceAll("(")).replaceAll(")")
                .replace("()", "");
        clean = SPACE_BEFORE_POINT.matcher(SPACES.matcher(clean).replaceAll(" ")).replaceAll("$1").strip();
        return clean.length() > MAX_REPLY_CHARS ? clean.substring(0, MAX_REPLY_CHARS).strip() + " …" : clean;
    }

    // ── quota ───────────────────────────────────────────────────────────────────────────────────────────

    /** The 19.8 increment in a rolled-back transaction: the exact refusal (QUOTA_EXCEEDED) or nothing kept. */
    private void checkQuota(String tenantSchema) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            quota.assertAndIncrementForSchema(tenantSchema, UsageMetric.AI_CREDITS, 1);
        });
    }

    /**
     * Whether the company may still ask a question now (the same rolled-back check as {@link #ask}); also gates the
     * model titles of new conversations.
     */
    public boolean hasCredit(String tenantSchema) {
        try {
            checkQuota(tenantSchema);
            return true;
        } catch (DomainException e) {
            return false;
        }
    }

    private Long consume(String tenantSchema) {
        try {
            tx.executeWithoutResult(status -> quota.assertAndIncrementForSchema(tenantSchema, UsageMetric.AI_CREDITS,
                    1));
        } catch (DomainException e) {
            if (e.getErrorCode() != ErrorCode.QUOTA_EXCEEDED) {
                throw e;
            }
            // used up by another consumer since the check: the answer is already paid for, deliver it uncounted
            log.warn("[AI] AI credits used up during the question: answer delivered without counting");
            return 0L;
        }
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
