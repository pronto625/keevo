package com.keevo.assistant.chat.adapter.out.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolCall;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolSpec;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Story 21.4.2 — the assistant's model through Anthropic's Messages API (Claude), with the official Anthropic Java
 * SDK. Same contract as the OpenAI-compatible adapter: the tools are declared (definitions only), the model asks for
 * calls, the assistant runs them itself on the request thread through the AI tool registry and hands the results back
 * as {@code tool_result} blocks.
 *
 * <p><b>Request shape</b> (Claude Haiku 5.5 rules): no {@code temperature} / {@code top_p} / {@code top_k} (non-default
 * values are refused), no assistant prefill, thinking turned off ({@code disabled} — accepted at effort ≤ high) with a
 * {@code low} effort: answers read figures computed by the tools, and the provider-neutral port does not carry the
 * opaque thinking blocks a tool loop would have to replay. Every round declares the same tools (the last one with
 * {@code tool_choice: none}): a conversation holding {@code tool_use} blocks must keep its tools, and the prefix stays
 * cacheable. Two cache breakpoints: after the system prompt (tools + system, shared by the questions of one role on one
 * day) and on the last message (each round reads the previous one). Content blocks are read by type, never by position.
 *
 * <p><b>Time:</b> each call waits at most the time left to the question ({@link AssistantExchange#timeLeft()}), capped
 * by the configured timeout; one retry on a transient failure (network, timeout, 408, 409, 429, 5xx) only when enough
 * time is left — the SDK's own retries are off, so a {@code retry-after} never runs past the question's deadline.
 *
 * <p>Any failure (network, timeout, HTTP error) or unusable answer (refusal, cut by {@code max_tokens}, empty) becomes
 * an {@link AssistantModelException} whose message names the provider and the failure kind only — never the question,
 * the answer, the response body or the key.
 */
public class AnthropicAssistantModel implements AssistantModel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAssistantModel.class);

    static final String PROVIDER = "anthropic";
    static final String DEFAULT_MODEL = "claude-haiku-5-5";
    static final int TITLE_TOKENS = 30;
    static final String TITLE_PROMPT = OpenAiCompatibleAssistantModel.TITLE_PROMPT;
    /** No retry when less is left: a second attempt could not finish anyway. */
    static final Duration MIN_RETRY_WINDOW = Duration.ofSeconds(5);
    static final Duration RETRY_PAUSE = Duration.ofSeconds(1);
    static final Duration TITLE_TIMEOUT = Duration.ofSeconds(10);

    private final String model;
    private final Duration callTimeout;
    /** Closed with the application context (OkHttp connection pools and dispatcher threads). */
    private final List<AnthropicClient> clients;
    private final ObjectMapper mapper;
    /** Tool declarations by spec (the registry's are stable): parsed once, not on every round. */
    private final Map<ToolSpec, Tool> declarations = new ConcurrentHashMap<>();
    private final BiFunction<MessageCreateParams, Duration, Message> chatCall;
    private final BiFunction<MessageCreateParams, Duration, Message> titleCall;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong cacheWriteTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    /**
     * Tokens since startup (answered calls only): cost follow-up, never any content. {@code inputTokens} includes the
     * {@code cachedInputTokens} read from the prompt cache (a tenth of the price) and the {@code cacheWriteTokens}
     * written to it (a quarter more).
     */
    public record Usage(long calls, long inputTokens, long cachedInputTokens, long cacheWriteTokens,
                        long outputTokens) {
    }

    /**
     * @param client      the SDK client (its own retries off: this class retries within the question's deadline)
     * @param callTimeout the longest wait of one question call
     */
    public AnthropicAssistantModel(AnthropicClient client, String model, Duration callTimeout, ObjectMapper mapper) {
        this(model, callTimeout, mapper,
                (p, t) -> client.messages().create(p, RequestOptions.builder().timeout(t).build()),
                (p, t) -> client.messages().create(p, RequestOptions.builder().timeout(t).build()), List.of(client));
    }

    /** Test seam: the calls are given (no network); each receives the timeout of that attempt. */
    AnthropicAssistantModel(String model, Duration callTimeout, ObjectMapper mapper,
                            BiFunction<MessageCreateParams, Duration, Message> chatCall,
                            BiFunction<MessageCreateParams, Duration, Message> titleCall) {
        this(model, callTimeout, mapper, chatCall, titleCall, List.of());
    }

    private AnthropicAssistantModel(String model, Duration callTimeout, ObjectMapper mapper,
                                    BiFunction<MessageCreateParams, Duration, Message> chatCall,
                                    BiFunction<MessageCreateParams, Duration, Message> titleCall,
                                    List<AnthropicClient> clients) {
        this.model = model;
        this.callTimeout = callTimeout;
        this.mapper = mapper;
        this.chatCall = chatCall;
        this.titleCall = titleCall;
        this.clients = clients;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AssistantTurn respond(AssistantExchange exchange) {
        MessageCreateParams params = params(exchange);
        Message response = callWithRetry(params, exchange.timeLeft());
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> append(text, t.text()));
            block.toolUse().ifPresent(t -> calls.add(new ToolCall(t.id(), t.name(), arguments(t))));
        }
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw AssistantModelException.unusableAnswer(PROVIDER + ": refusal");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            // a tool call cut mid-JSON or an answer cut mid-sentence (or mid-figure) is never delivered
            throw AssistantModelException.unusableAnswer(PROVIDER + ": answer cut by max_tokens");
        }
        if (calls.isEmpty() && text.isEmpty()) {
            throw new AssistantModelException(PROVIDER + ": empty answer (" + stop + ")");
        }
        return new AssistantTurn(text.isEmpty() ? null : text.toString(), calls);
    }

    @Override
    public String title(String firstMessage) {
        MessageCreateParams params = base(TITLE_TOKENS).system(TITLE_PROMPT).addUserMessage(firstMessage).build();
        Message response = call(titleCall, params, TITLE_TIMEOUT);
        StringBuilder text = new StringBuilder();
        response.content().forEach(b -> b.text().ifPresent(t -> append(text, t.text())));
        if (text.isEmpty()) {
            throw new AssistantModelException(PROVIDER + ": empty title");
        }
        return text.toString();
    }

    /** The Messages API request of one exchange (package-private: tests check its shape without a network). */
    MessageCreateParams params(AssistantExchange exchange) {
        MessageCreateParams.Builder b = base(exchange.maxOutputTokens()).systemOfTextBlockParams(List.of(
                TextBlockParam.builder().text(exchange.system()).cacheControl(ephemeral()).build()));
        List<AssistantExchange.Message> messages = exchange.messages();
        for (int i = 0; i < messages.size(); i++) {
            b.addMessage(toParam(messages.get(i), i == messages.size() - 1));
        }
        for (ToolSpec spec : exchange.tools()) {
            b.addTool(declarations.computeIfAbsent(spec, this::declaration));
        }
        if (exchange.answerOnly() && !exchange.tools().isEmpty()) {
            b.toolChoice(ToolChoiceNone.builder().build());
        }
        return b.build();
    }

    private MessageCreateParams.Builder base(int maxTokens) {
        return MessageCreateParams.builder().model(model).maxTokens(maxTokens)
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
    }

    private static CacheControlEphemeral ephemeral() {
        return CacheControlEphemeral.builder().build();
    }

    /** One message; {@code breakpoint}: the last one, whose last block carries the second cache breakpoint. */
    private MessageParam toParam(AssistantExchange.Message m, boolean breakpoint) {
        return switch (m.role()) {
            case USER -> MessageParam.ofUserOfContentBlockParams(List.of(text(m.text(), breakpoint)));
            case ASSISTANT -> {
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (m.text() != null && !m.text().isBlank() || m.toolCalls().isEmpty()) {
                    blocks.add(text(m.text(), breakpoint && m.toolCalls().isEmpty()));
                }
                for (int i = 0; i < m.toolCalls().size(); i++) {
                    ToolCall c = m.toolCalls().get(i);
                    ToolUseBlockParam.Builder use = ToolUseBlockParam.builder().id(c.id()).name(c.name())
                            .input(input(c.arguments()));
                    if (breakpoint && i == m.toolCalls().size() - 1) {
                        use.cacheControl(ephemeral());
                    }
                    blocks.add(ContentBlockParam.ofToolUse(use.build()));
                }
                yield MessageParam.ofAssistantOfContentBlockParams(blocks);
            }
            case TOOL -> {
                List<ContentBlockParam> blocks = new ArrayList<>();
                for (int i = 0; i < m.toolResults().size(); i++) {
                    AssistantExchange.ToolResult r = m.toolResults().get(i);
                    ToolResultBlockParam.Builder result = ToolResultBlockParam.builder().toolUseId(r.callId())
                            .content(r.content() == null ? "{}" : r.content());
                    if (breakpoint && i == m.toolResults().size() - 1) {
                        result.cacheControl(ephemeral());
                    }
                    blocks.add(ContentBlockParam.ofToolResult(result.build()));
                }
                yield MessageParam.ofUserOfContentBlockParams(blocks);
            }
        };
    }

    private static ContentBlockParam text(String text, boolean breakpoint) {
        TextBlockParam.Builder b = TextBlockParam.builder().text(text == null || text.isBlank() ? "…" : text);
        if (breakpoint) {
            b.cacheControl(ephemeral());
        }
        return ContentBlockParam.ofText(b.build());
    }

    /** The arguments of a call (a JSON object, as the model sent them) as the tool_use input to replay. */
    private ToolUseBlockParam.Input input(String arguments) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        JsonNode node = parse(arguments);
        if (!(node instanceof ObjectNode object)) {
            throw new AssistantModelException(PROVIDER + ": tool input to replay is not an object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            input.putAdditionalProperty(f.getKey(), JsonValue.fromJsonNode(f.getValue()));
        }
        return input.build();
    }

    private String arguments(ToolUseBlock block) {
        try {
            Object value = block._input().convert(Object.class);
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new AssistantModelException(PROVIDER + ": unreadable tool input");
        }
    }

    /** A tool of the registry declared to Claude: its JSON schema split into properties, required and the rest. */
    Tool declaration(ToolSpec spec) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        JsonNode node = parse(spec.inputSchema());
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                switch (f.getKey()) {
                    case "type", "$schema" -> {
                        // type is "object" (the SDK's default); $schema is not part of a tool schema
                    }
                    case "properties" -> f.getValue().fields().forEachRemaining(p ->
                            properties.putAdditionalProperty(p.getKey(), JsonValue.fromJsonNode(p.getValue())));
                    case "required" -> f.getValue().forEach(r -> schema.addRequired(r.asText()));
                    default -> schema.putAdditionalProperty(f.getKey(), JsonValue.fromJsonNode(f.getValue()));
                }
            }
        }
        return Tool.builder().name(spec.name()).description(spec.description())
                .inputSchema(schema.properties(properties.build()).build()).build();
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.nullNode();
        }
    }

    /** A question call: within the time left, one retry on a transient failure when enough time remains. */
    private Message callWithRetry(MessageCreateParams params, Duration timeLeft) {
        // without a deadline: the configured timeout per attempt, room for the one retry
        long deadline = System.nanoTime() + (timeLeft == null ? callTimeout.multipliedBy(2) : timeLeft).toNanos();
        try {
            return call(chatCall, params, attemptTimeout(deadline));
        } catch (AssistantModelException e) {
            if (!transientFailure(e.getCause())
                    || Duration.ofNanos(deadline - System.nanoTime()).compareTo(MIN_RETRY_WINDOW) < 0) {
                throw e;
            }
            pause();
            log.debug("[AI] anthropic call retried after {}", e.getMessage());
            return call(chatCall, params, attemptTimeout(deadline));
        }
    }

    private Duration attemptTimeout(long deadline) {
        Duration left = Duration.ofNanos(deadline - System.nanoTime());
        if (left.isNegative() || left.isZero()) {
            throw new AssistantModelException(PROVIDER + ": no time left for the call");
        }
        return left.compareTo(callTimeout) < 0 ? left : callTimeout;
    }

    /** Network, timeout, or HTTP 408 / 409 / 429 / 5xx: worth one more attempt. */
    static boolean transientFailure(Throwable cause) {
        if (cause instanceof AnthropicIoException || cause instanceof AnthropicRetryableException) {
            return true;
        }
        if (cause instanceof AnthropicServiceException s) {
            int status = s.statusCode();
            return status == 408 || status == 409 || status == 429 || status >= 500;
        }
        return false;
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantModelException(PROVIDER + ": interrupted");
        }
    }

    private Message call(BiFunction<MessageCreateParams, Duration, Message> call, MessageCreateParams params,
                         Duration timeout) {
        Message response;
        try {
            response = call.apply(params, timeout);
        } catch (RuntimeException e) {
            // AnthropicException and its subtypes (timeouts, HTTP errors): their message may quote the response body,
            // so only the type is kept
            throw new AssistantModelException(PROVIDER + " call failed: " + e.getClass().getSimpleName(), e);
        }
        if (response == null) {
            throw new AssistantModelException(PROVIDER + ": no result");
        }
        calls.incrementAndGet();
        if (response._usage().isMissing() || response._usage().isNull()) {
            return response;
        }
        long cached = response.usage().cacheReadInputTokens().orElse(0L);
        long written = response.usage().cacheCreationInputTokens().orElse(0L);
        long in = response.usage().inputTokens() + cached + written;
        long out = response.usage().outputTokens();
        inputTokens.addAndGet(in);
        cachedInputTokens.addAndGet(cached);
        cacheWriteTokens.addAndGet(written);
        outputTokens.addAndGet(out);
        log.debug("[AI] anthropic call stop={} inputTokens={} cachedInputTokens={} cacheWriteTokens={} "
                + "outputTokens={}", response.stopReason().orElse(null), in, cached, written, out);
        return response;
    }

    private static void append(StringBuilder text, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(part.strip());
    }

    public Usage usage() {
        return new Usage(calls.get(), inputTokens.get(), cachedInputTokens.get(), cacheWriteTokens.get(),
                outputTokens.get());
    }

    @Override
    public void close() {
        clients.forEach(AnthropicClient::close);
    }

    /** The configured model id (logs and tests). */
    String model() {
        return model;
    }

    /** The configured longest wait of one question call (tests). */
    Duration callTimeout() {
        return callTimeout;
    }
}
