package com.keevo.whatsappcloud.graph.adapter.out;

import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.MetaTemplate;
import com.keevo.whatsappcloud.graph.domain.model.PhoneNumberInfo;
import com.keevo.whatsappcloud.graph.domain.model.TemplateDraft;
import com.keevo.whatsappcloud.graph.domain.port.out.MetaGraphClient;

import java.util.List;
import java.util.Map;

/** Story 21.5 — {@code keevo.whatsapp-cloud.graph=disabled} (default): no Meta app configured, every call unavailable. */
public class DisabledMetaGraphClient implements MetaGraphClient {

    private static MetaGraphException off() {
        return MetaGraphException.unavailable("WhatsApp Cloud is not configured (keevo.whatsapp-cloud.graph=disabled)");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String exchangeCode(String code) {
        throw off();
    }

    @Override
    public PhoneNumberInfo phoneNumber(String wabaId, String phoneNumberId, String token) {
        throw off();
    }

    @Override
    public void subscribeApp(String wabaId, String token) {
        throw off();
    }

    @Override
    public String sendText(String phoneNumberId, String token, String to, String body) {
        throw off();
    }

    @Override
    public String sendTemplate(String phoneNumberId, String token, String to, String name, String language,
                               List<Map<String, Object>> components) {
        throw off();
    }

    @Override
    public String uploadMedia(String phoneNumberId, String token, byte[] content, String mimeType, String filename) {
        throw off();
    }

    @Override
    public String uploadHeaderSample(String token, byte[] content, String mimeType, String filename) {
        throw off();
    }

    @Override
    public MetaTemplate createTemplate(String wabaId, String token, TemplateDraft draft) {
        throw off();
    }

    @Override
    public List<MetaTemplate> listTemplates(String wabaId, String token) {
        throw off();
    }

    @Override
    public void deleteTemplate(String wabaId, String token, String name) {
        throw off();
    }
}
