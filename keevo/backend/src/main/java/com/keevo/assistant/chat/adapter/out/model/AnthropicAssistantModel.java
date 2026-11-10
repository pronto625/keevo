package com.keevo.assistant.chat.adapter.out.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Story 21.4.2 — the assistant's model through Anthropic's Messages API (Claude), with the official Anthropic Java
 * SDK. Same contract as the OpenAI-compatible adapter: the tools are declared (definitions only), the model asks for
 * calls, the assistant runs them itself on the request thread through the AI tool registry and hands the results back
 * as {@code tool_result} blocks.
 *
 * <p><b>Request shape</b> (Claude Haiku 5.5 rules): no {@code temperature} / {@code top_p} / {@code top_k} (non-default
 * values are refused), no assistant prefill, thinking turned off ({@code disabled} — accepted at effort ≤ high) with a
 * {@code low} effort: answers read figures computed by the tools, and the assistant's loop is not append-only (the
 * last round is sent without tools), which replayed thinking blocks would not survive. Content blocks are read by
 * type, never by position.
 *
 * <p>Any failure (network, timeout after the SDK's retries, HTTP error, refusal, truncated or empty answer) becomes an
 * {@link AssistantModelException} whose message names the provider and the failure kind only — never the question,
 * the answer, the response body or the key.
 */
public class AnthropicAssistantModel implements AssistantModel, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAssistantModel.class);

    static final String PROVIDER = "anthropic";
    static final String DEFAULT_MODEL = "claude-haiku-5-5";
    static final int TITLE_TOKENS = 30;
    static final String TITLE_PROMPT = OpenAiCompatibleAssistantModel.TITLE_PROMPT;

    private final String model;
    /** Closed with the application context (OkHttp connection pools and dispatcher threads). */
    private final List<AnthropicClient> clients;
    private final ObjectMapper mapper;
    /** Tool declarations by spec (the registry's are stable): parsed once, not on every round. */
    private final Map<ToolSpec, Tool> declarations = new ConcurrentHashMap<>();
    private final Function<MessageCreateParams, Message> chatCall;
    private final Function<MessageCreateParams, Message> titleCall;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();

    /**
     * Tokens since startup (answered calls only): cost follow-up, never any content. {@code inputTokens} includes the
     * {@code cachedInputTokens} read from the prompt cache.
     */
    public record Usage(long calls, long inputTokens, long cachedInputTokens, long outputTokens) {
    }

    /**
     * @param chat   client of the questions (timeout and retries of the assistant)
     * @param titles client of the conversation titles (short timeout, no retry)
     */
    public AnthropicAssistantModel(AnthropicClient chat, AnthropicClient titles, String model, ObjectMapper mapper) {
        this(model, mapper, p -> chat.messages().create(p), p -> titles.messages().create(p), List.of(chat, titles));
    }

    /** Test seam: the calls are given (no network). */
    AnthropicAssistantModel(String model, ObjectMapper mapper, Function<MessageCreateParams, Message> chatCall,
                            Function<MessageCreateParams, Message> titleCall) {
        this(model, mapper, chatCall, titleCall, List.of());
    }

    private AnthropicAssistantModel(String model, ObjectMapper mapper, Function<MessageCreateParams, Message> chatCall,
                                    Function<MessageCreateParams, Message> titleCall, List<AnthropicClient> clients) {
        this.clients = clients;
        this.model = model;
        this.mapper = mapper;
        this.chatCall = chatCall;
        this.titleCall = titleCall;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AssistantTurn respond(AssistantExchange exchange) {
        MessageCreateParams params = params(exchange);
        Message response = call(chatCall, params);
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> append(text, t.text()));
            block.toolUse().ifPresent(t -> calls.add(new ToolCall(t.id(), t.name(), arguments(t))));
        }
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new AssistantModelException(PROVIDER + ": refusal");
        }
        if (StopReason.MAX_TOKENS.equals(stop) && !calls.isEmpty()) {
            throw new AssistantModelException(PROVIDER + ": tool call cut by max_tokens");
        }
        if (calls.isEmpty() && text.isEmpty()) {
            throw new AssistantModelException(PROVIDER + ": empty answer (" + stop + ")");
        }
        return new AssistantTurn(text.isEmpty() ? null : text.toString(), calls);
    }

    @Override
    public String title(String firstMessage) {
        MessageCreateParams params = base(TITLE_TOKENS).system(TITLE_PROMPT).addUserMessage(firstMessage).build();
        Message response = call(titleCall, params);
        StringBuilder text = new StringBuilder();
        response.content().forEach(b -> b.text().ifPresent(t -> append(text, t.text())));
        if (text.isEmpty()) {
            throw new AssistantModelException(PROVIDER + ": empty title");
        }
        return text.toString();
    }

    /** The Messages API request of one exchange (package-private: tests check its shape without a network). */
    MessageCreateParams params(AssistantExchange exchange) {
        // tools then system are the stable prefix of every round of a question (and of the questions of one role on
        // one day): cached, read at a tenth of the input price; messages stay after the breakpoint
        MessageCreateParams.Builder b = base(exchange.maxOutputTokens()).systemOfTextBlockParams(List.of(
                TextBlockParam.builder().text(exchange.system())
                        .cacheControl(CacheControlEphemeral.builder().build()).build()));
        for (AssistantExchange.Message m : exchange.messages()) {
            b.addMessage(toParam(m));
        }
        for (ToolSpec spec : exchange.tools()) {
            b.addTool(declarations.computeIfAbsent(spec, this::declaration));
        }
        return b.build();
    }

    private MessageCreateParams.Builder base(int maxTokens) {
        return MessageCreateParams.builder().model(model).maxTokens(maxTokens)
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
    }

    private MessageParam toParam(AssistantExchange.Message m) {
        return switch (m.role()) {
            case USER -> MessageParam.ofUser(m.text() == null || m.text().isBlank() ? "…" : m.text());
            case ASSISTANT -> {
                if (m.toolCalls().isEmpty()) {
                    yield MessageParam.ofAssistant(m.text() == null || m.text().isBlank() ? "…" : m.text());
                }
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (m.text() != null && !m.text().isBlank()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(m.text()).build()));
                }
                for (ToolCall c : m.toolCalls()) {
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(c.id()).name(c.name())
                            .input(input(c.arguments())).build()));
                }
                yield MessageParam.ofAssistantOfContentBlockParams(blocks);
            }
            case TOOL -> MessageParam.ofUserOfContentBlockParams(m.toolResults().stream()
                    .map(r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(r.callId())
                            .content(r.content() == null ? "{}" : r.content()).build()))
                    .toList());
        };
    }

    /** The arguments of a call (JSON object) as the tool_use input to replay. */
    private ToolUseBlockParam.Input input(String arguments) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        JsonNode node = parse(arguments);
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                input.putAdditionalProperty(f.getKey(), JsonValue.fromJsonNode(f.getValue()));
            }
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
            return mapper.createObjectNode();
        }
    }

    private Message call(Function<MessageCreateParams, Message> call, MessageCreateParams params) {
        Message response;
        try {
            response = call.apply(params);
        } catch (RuntimeException e) {
            // AnthropicException and its subtypes (timeouts, HTTP errors after the retries): their message may quote
            // the response body, so only the type is kept
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
        long in = response.usage().inputTokens() + cached + response.usage().cacheCreationInputTokens().orElse(0L);
        long out = response.usage().outputTokens();
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);
        cachedInputTokens.addAndGet(cached);
        log.debug("[AI] anthropic call stop={} inputTokens={} cachedInputTokens={} outputTokens={}",
                response.stopReason().orElse(null), in, cached, out);
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
        return new Usage(calls.get(), inputTokens.get(), cachedInputTokens.get(), outputTokens.get());
    }

    @Override
    public void close() {
        clients.forEach(AnthropicClient::close);
    }

    /** The configured model id (logs and tests). */
    String model() {
        return model;
    }
}
