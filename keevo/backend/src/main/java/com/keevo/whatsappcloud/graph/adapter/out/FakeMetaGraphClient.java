package com.keevo.whatsappcloud.graph.adapter.out;

import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.MetaTemplate;
import com.keevo.whatsappcloud.graph.domain.model.PhoneNumberInfo;
import com.keevo.whatsappcloud.graph.domain.model.TemplateDraft;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Story 21.5 — in-memory {@link MetaGraphClient} for the {@code dev} and {@code test} profiles
 * ({@code keevo.whatsapp-cloud.graph=fake}): no network, deterministic, every call recorded ({@link #calls()}) so tests
 * can check what would have reached Meta. Conventions:
 * <ul>
 *   <li>a signup code starting with {@code invalid} is refused; any other gives the token {@code fake-token-<code>};</li>
 *   <li>a number id starting with {@code 0} is not in the WABA; any other is « +237 6 90 00 <last 4 digits> »;</li>
 *   <li>a recipient whose number (after an optional 237) starts with {@code 999} is refused by Meta, {@code 998} times
 *       out (unknown outcome), {@code 997} is refused by Meta's throughput limit (code 130429, Story 21.6);</li>
 *   <li>templates are kept per WABA: created PENDING (a second creation of the same name / language is refused), the
 *       review result is set by {@link #review};</li>
 * </ul>
 */
public class FakeMetaGraphClient implements MetaGraphClient {

    /** One recorded call: the operation, the number or WABA addressed, and a short detail (recipient, name…). */
    public record Call(String operation, String target, String detail) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, MetaTemplate>> templates = new ConcurrentHashMap<>();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String exchangeCode(String code) {
        record("exchange-code", "", "");
        if (code.startsWith("invalid")) {
            throw MetaGraphException.refused("exchange-code: HTTP 400", "Code expiré : reconnectez WhatsApp", 100);
        }
        return "fake-token-" + code;
    }

    @Override
    public PhoneNumberInfo phoneNumber(String wabaId, String phoneNumberId, String token) {
        record("phone-numbers", wabaId, phoneNumberId);
        if (phoneNumberId.startsWith("0")) {
            throw MetaGraphException.refused("phone-numbers: number not in the WABA", null, -1);
        }
        String digits = phoneNumberId.length() >= 4 ? phoneNumberId.substring(phoneNumberId.length() - 4)
                : String.format("%4s", phoneNumberId).replace(' ', '0');
        return new PhoneNumberInfo(phoneNumberId, "+237 6 90 00 " + digits, "Boutique test");
    }

    @Override
    public void subscribeApp(String wabaId, String token) {
        record("subscribe-app", wabaId, "");
    }

    @Override
    public String sendText(String phoneNumberId, String token, String to, String body) {
        record("send-text", phoneNumberId, to);
        return outcome(to);
    }

    @Override
    public String sendTemplate(String phoneNumberId, String token, String to, String name, String language,
                               List<Map<String, Object>> components) {
        record("send-template", phoneNumberId, to + ":" + name + ":" + language + ":" + components.size());
        return outcome(to);
    }

    @Override
    public String uploadMedia(String phoneNumberId, String token, byte[] content, String mimeType, String filename) {
        record("upload-media", phoneNumberId, mimeType + ":" + content.length);
        return "fake-media-" + UUID.randomUUID();
    }

    @Override
    public String uploadHeaderSample(String token, byte[] content, String mimeType, String filename) {
        record("upload-sample", "", mimeType + ":" + content.length);
        return "fake-handle-" + UUID.randomUUID();
    }

    @Override
    public MetaTemplate createTemplate(String wabaId, String token, TemplateDraft draft) {
        record("create-template", wabaId, draft.name() + ":" + draft.language());
        Map<String, MetaTemplate> waba = templates.computeIfAbsent(wabaId, k -> new ConcurrentHashMap<>());
        MetaTemplate created = new MetaTemplate(String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits())),
                draft.name(), draft.language(), "PENDING", null, draft.category(),
                draft.headerFormat() == null ? "NONE" : draft.headerFormat(), draft.bodyText());
        if (waba.putIfAbsent(key(draft.name(), draft.language()), created) != null) {
            throw MetaGraphException.refused("create-template: HTTP 400",
                    "Ce modèle existe déjà dans cette langue", 2388024);
        }
        return created;
    }

    @Override
    public List<MetaTemplate> listTemplates(String wabaId, String token) {
        record("list-templates", wabaId, "");
        return new ArrayList<>(templates.getOrDefault(wabaId, Map.of()).values());
    }

    @Override
    public void deleteTemplate(String wabaId, String token, String name) {
        record("delete-template", wabaId, name);
        Map<String, MetaTemplate> waba = templates.get(wabaId);
        if (waba != null) {
            waba.values().removeIf(t -> t.name().equals(name));
        }
    }

    /** Test / dev hook: Meta's review result of a template of {@code wabaId}; returns it, or {@code null}. */
    public MetaTemplate review(String wabaId, String name, String language, String status, String reason) {
        Map<String, MetaTemplate> waba = templates.get(wabaId);
        if (waba == null) {
            return null;
        }
        return waba.computeIfPresent(key(name, language),
                (k, t) -> new MetaTemplate(t.id(), t.name(), t.language(), status, reason, t.category(),
                        t.headerFormat(), t.bodyText()));
    }

    /** Every recorded call, oldest first. */
    public List<Call> calls() {
        return Collections.unmodifiableList(new ArrayList<>(calls));
    }

    /** Recorded calls of one operation addressed to {@code target}. */
    public List<Call> calls(String operation, String target) {
        return calls.stream().filter(c -> c.operation().equals(operation) && c.target().equals(target)).toList();
    }

    private static String outcome(String to) {
        String local = to.startsWith("237") ? to.substring(3) : to;
        if (local.startsWith("999")) {
            throw MetaGraphException.refused("send: HTTP 400", "Ce numéro n'est pas sur WhatsApp", 131026);
        }
        if (local.startsWith("997")) {
            throw MetaGraphException.refused("send: HTTP 429", null, 130429);
        }
        if (local.startsWith("998")) {
            throw MetaGraphException.unknown("send: read timeout");
        }
        return "wamid.fake-" + UUID.randomUUID();
    }

    private void record(String operation, String target, String detail) {
        calls.add(new Call(operation, target, detail));
        if (calls.size() > 10_000) {
            calls.remove(0);
        }
    }

    private static String key(String name, String language) {
        return name + "|" + language;
    }
}
