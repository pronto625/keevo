package com.keevo.assistant.chat.adapter.out.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keevo.assistant.chat.domain.model.AssistantExchange;
import com.keevo.assistant.chat.domain.model.AssistantExchange.Message;
import com.keevo.assistant.chat.domain.model.AssistantExchange.Role;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolCall;
import com.keevo.assistant.chat.domain.model.AssistantExchange.ToolResult;
import com.keevo.assistant.chat.domain.model.AssistantTurn;
import com.keevo.assistant.chat.domain.port.out.AssistantModel;
import com.keevo.assistant.chat.domain.port.out.AssistantModelException;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Story 21.4 — a scripted fake model (dev and tests only, {@code keevo.ai.provider=fake}): never calls a network. A
 * script maps a question to tool steps and an answer template, like a model would — it asks for one tool per turn
 * (arguments may reuse an earlier result), then answers with the figures of the results.
 *
 * <p>Script (JSON): {@code {"rules": [{"question": "…", "keywords": ["…"], "steps": [{"tool": "…", "args": {…}}],
 * "answer": "…"}], "fallback": "…"}}. A rule matches the question exactly (case and spaces ignored) or, without
 * {@code question}, when every keyword is contained in it; the first match wins, else the fallback answer (no tool).
 * Placeholders {@code {{tool:/json/pointer}}} read the last result of {@code tool}; a pointer segment {@code [field=value]}
 * picks the element of an array whose {@code field} equals {@code value}. In arguments a placeholder is replaced by
 * the raw value; in the answer numbers are grouped by thousands with spaces (« 450 000 ») and decimals use a comma.
 * A refused tool (result {@code {"erreur": …}}) ends the script with « Je ne peux pas répondre : … ». A rule with
 * {@code "fail": true} simulates a provider failure once its steps are done (tests).
 */
public class ScriptedAssistantModel implements AssistantModel {

    static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+):([^}]*)}}");
    static final Pattern FILTER = Pattern.compile("([^\\[]*)\\[([^=\\]]+)=([^\\]]*)]");

    record Step(String tool, String args) {
    }

    record Rule(String question, List<String> keywords, List<Step> steps, String answer, boolean fail) {
    }

    record Script(List<Rule> rules, String fallback) {
    }

    private final Script initial;
    private final java.util.concurrent.atomic.AtomicReference<Script> script;
    private final ObjectMapper mapper;

    public ScriptedAssistantModel(JsonNode script, ObjectMapper mapper) {
        this.mapper = mapper;
        this.initial = parse(script);
        this.script = new java.util.concurrent.atomic.AtomicReference<>(initial);
    }

    /** Tests only: answers with {@code script} until {@link #reset()}. */
    public void useScript(JsonNode script) {
        this.script.set(parse(script));
    }

    /** Tests only: back to the script given at startup. */
    public void reset() {
        this.script.set(initial);
    }

    private static Script parse(JsonNode script) {
        List<Rule> parsed = new ArrayList<>();
        for (JsonNode r : script.path("rules")) {
            List<String> keywords = new ArrayList<>();
            r.path("keywords").forEach(k -> keywords.add(normalize(k.asText())));
            List<Step> steps = new ArrayList<>();
            for (JsonNode s : r.path("steps")) {
                JsonNode args = s.path("args");
                steps.add(new Step(s.path("tool").asText(), args.isMissingNode() || args.isNull() ? "{}"
                        : args.toString()));
            }
            String question = r.hasNonNull("question") ? normalize(r.get("question").asText()) : null;
            parsed.add(new Rule(question, List.copyOf(keywords), List.copyOf(steps), r.path("answer").asText(""),
                    r.path("fail").asBoolean(false)));
        }
        return new Script(List.copyOf(parsed),
                script.path("fallback").asText("Je suis l'assistant simulé : posez une question prévue."));
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AssistantTurn respond(AssistantExchange exchange) {
        List<Message> messages = exchange.messages();
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) {
            throw new AssistantModelException("scripted model: no question");
        }
        Map<String, JsonNode> results = new HashMap<>();
        int done = 0;
        String refusal = null;
        for (int i = lastUser + 1; i < messages.size(); i++) {
            for (ToolResult r : messages.get(i).toolResults()) {
                done++;
                JsonNode node = parse(r.content());
                results.put(r.name(), node);
                if (refusal == null && node.isObject() && node.hasNonNull("erreur")) {
                    refusal = node.get("erreur").asText();
                }
            }
        }
        Rule rule = match(messages.get(lastUser).text());
        if (rule == null) {
            return AssistantTurn.answer(script.get().fallback());
        }
        if (rule.fail() && done == rule.steps().size()) {
            throw new AssistantModelException("scripted provider failure");
        }
        if (refusal != null) {
            return AssistantTurn.answer("Je ne peux pas répondre : " + refusal);
        }
        if (done < rule.steps().size() && !exchange.tools().isEmpty()) {
            Step step = rule.steps().get(done);
            return new AssistantTurn(null, List.of(new ToolCall("call_" + done, step.tool(),
                    render(step.args(), results, false))));
        }
        return AssistantTurn.answer(render(rule.answer(), results, true));
    }

    @Override
    public String title(String firstMessage) {
        String[] words = firstMessage == null ? new String[0] : firstMessage.strip().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 6)));
    }

    private Rule match(String question) {
        String q = normalize(question);
        for (Rule r : script.get().rules()) {
            if (r.question() != null ? r.question().equals(q)
                    : !r.keywords().isEmpty() && r.keywords().stream().allMatch(q::contains)) {
                return r;
            }
        }
        return null;
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json == null ? "null" : json);
        } catch (Exception e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("erreur", "résultat illisible");
            return error;
        }
    }

    static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String render(String template, Map<String, JsonNode> results, boolean forPeople) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            JsonNode value = resolve(results.get(m.group(1)), m.group(2));
            String text;
            if (value == null || value.isMissingNode() || value.isNull()) {
                text = forPeople ? "—" : "";
            } else if (forPeople && value.isNumber()) {
                text = format(value.decimalValue());
            } else {
                text = value.isValueNode() ? value.asText() : value.toString();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** JSON pointer with {@code [field=value]} segments selecting an array element. */
    static JsonNode resolve(JsonNode root, String pointer) {
        if (root == null) {
            return null;
        }
        JsonNode node = root;
        for (String segment : pointer.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            Matcher f = FILTER.matcher(segment);
            if (f.matches()) {
                if (!f.group(1).isEmpty()) {
                    node = node.path(f.group(1));
                }
                JsonNode found = null;
                for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                    JsonNode e = it.next();
                    if (f.group(3).equals(e.path(f.group(2)).asText())) {
                        found = e;
                        break;
                    }
                }
                if (found == null) {
                    return null;
                }
                node = found;
            } else if (node.isArray() && segment.chars().allMatch(Character::isDigit)) {
                node = node.path(Integer.parseInt(segment));
            } else {
                node = node.path(segment);
            }
        }
        return node;
    }

    /** « 450 000 », « 12,5 » — French grouping with plain spaces. */
    static String format(BigDecimal value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat("#,##0.##", symbols);
        return f.format(value);
    }
}
