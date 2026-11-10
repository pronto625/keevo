package com.keevo.whatsappcloud.graph.domain.port.out;

import com.keevo.whatsappcloud.graph.domain.model.MetaGraphException;
import com.keevo.whatsappcloud.graph.domain.model.MetaTemplate;
import com.keevo.whatsappcloud.graph.domain.model.PhoneNumberInfo;
import com.keevo.whatsappcloud.graph.domain.model.TemplateDraft;

import java.util.List;
import java.util.Map;

/**
 * Story 21.5 — port to Meta's Graph API for a company's own WhatsApp Business account (Strategy chosen once at startup
 * by {@code keevo.whatsapp-cloud.graph}: {@code meta} = the real API, {@code fake} = in memory for dev and tests,
 * {@code disabled} = every call {@link MetaGraphException.Kind#UNAVAILABLE}). Ported from api-bref
 * {@code WhatsAppService}. Every call takes the company's decrypted token; none logs it. Phone numbers are given in
 * Meta's format (digits only, country code included).
 *
 * <p>Every method throws {@link MetaGraphException} on failure (refused, unreachable, unknown outcome).
 */
public interface MetaGraphClient {

    /** False for the disabled strategy: connecting is refused (503) without any call. */
    boolean isEnabled();

    /** Embedded signup: exchanges the one-time {@code code} for the business access token (app id + app secret). */
    String exchangeCode(String code);

    /** The number {@code phoneNumberId} of the WABA {@code wabaId}; refused when the WABA has no such number. */
    PhoneNumberInfo phoneNumber(String wabaId, String phoneNumberId, String token);

    /** Subscribes the Keevo app to the WABA's webhooks (messages, statuses, template reviews). */
    void subscribeApp(String wabaId, String token);

    /** Free text (only inside the contact's 24-hour window, else Meta refuses) → Meta's message id. */
    String sendText(String phoneNumberId, String token, String to, String body);

    /** A message of an approved template, with its components ({@code header}, {@code body}…) → Meta's message id. */
    String sendTemplate(String phoneNumberId, String token, String to, String name, String language,
                        List<Map<String, Object>> components);

    /** Uploads a media file to the number (e.g. the invoice PDF) → Meta's media id. */
    String uploadMedia(String phoneNumberId, String token, byte[] content, String mimeType, String filename);

    /** Uploads the example media of a template header (resumable upload of the app) → Meta's header handle. */
    String uploadHeaderSample(String token, byte[] content, String mimeType, String filename);

    /** Submits a template for Meta's review → the template as Meta answers it (status PENDING, usually). */
    MetaTemplate createTemplate(String wabaId, String token, TemplateDraft draft);

    /** The WABA's templates (at most 200) with their review status. */
    List<MetaTemplate> listTemplates(String wabaId, String token);

    /** Deletes a template (every language) from the WABA. */
    void deleteTemplate(String wabaId, String token, String name);
}
