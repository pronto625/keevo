package com.keevo.whatsappcloud.template.domain.model;

import com.keevo.shared.capability.notification.PlatformWhatsApp.HeaderMedia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Story 21.6 — Builder of the {@code components} Meta expects with a template message (port of Bref
 * {@code CampaignService.whatsAppComponents} / {@code CustomerCampaignService.components}): the media of an IMAGE /
 * VIDEO / DOCUMENT header (a media id uploaded to the sending number, or a public https link Meta fetches itself —
 * never Keevo), then the body values in the template's variable order, each with its {@code parameter_name} when the
 * template uses named variables ({@code {{name}}}). Pure: no I/O.
 */
public final class TemplateComponents {

    public static final Set<String> MEDIA_HEADERS = Set.of("IMAGE", "VIDEO", "DOCUMENT");
    public static final int MAX_LINK = 2048;
    public static final int MAX_MEDIA_ID = 128;

    private TemplateComponents() {
    }

    public static boolean isMediaHeader(String headerFormat) {
        return headerFormat != null && MEDIA_HEADERS.contains(headerFormat.toUpperCase(Locale.ROOT));
    }

    /**
     * The French reason the header media does not fit the template, or {@code null} when it does: a media template
     * needs exactly one of an uploaded media id or an {@code https://} link; any other template takes none.
     */
    public static String headerProblem(String headerFormat, HeaderMedia header) {
        String id = header == null ? null : blankToNull(header.id());
        String link = header == null ? null : blankToNull(header.link());
        if (!isMediaHeader(headerFormat)) {
            return id == null && link == null ? null : "Ce modèle n'a pas d'en-tête image, vidéo ou document";
        }
        if ((id == null) == (link == null)) {
            return "Ce modèle a un en-tête " + mediaLabel(headerFormat)
                    + " : joignez le fichier ou indiquez un lien https";
        }
        if (id != null && (id.length() > MAX_MEDIA_ID || !id.matches("[A-Za-z0-9._:-]+"))) {
            return "Fichier joint invalide : déposez-le de nouveau";
        }
        if (link != null && (link.length() > MAX_LINK || !link.toLowerCase(Locale.ROOT).startsWith("https://")
                || link.chars().anyMatch(Character::isWhitespace))) {
            return "Le lien du média doit commencer par https:// (2048 caractères au plus)";
        }
        return null;
    }

    /** The components of one message ({@code values} one per variable of {@code bodyText}, in its order). */
    public static List<Map<String, Object>> build(String bodyText, List<String> values, String headerFormat,
                                                  HeaderMedia header) {
        List<Map<String, Object>> components = new ArrayList<>();
        if (isMediaHeader(headerFormat) && header != null
                && (blankToNull(header.id()) != null || blankToNull(header.link()) != null)) {
            String kind = headerFormat.toLowerCase(Locale.ROOT);
            Map<String, Object> media = new LinkedHashMap<>();
            if (blankToNull(header.id()) != null) {
                media.put("id", header.id().strip());
            } else {
                media.put("link", header.link().strip());
            }
            components.add(Map.of("type", "header", "parameters", List.of(Map.of("type", kind, kind, media))));
        }
        List<String> keys = TemplateText.variables(bodyText);
        if (!values.isEmpty()) {
            boolean named = keys.stream().anyMatch(k -> !k.matches("\\d+"));
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", "text");
                if (named && i < keys.size()) {
                    p.put("parameter_name", keys.get(i));
                }
                p.put("text", values.get(i));
                parameters.add(p);
            }
            components.add(Map.of("type", "body", "parameters", parameters));
        }
        return components;
    }

    static String mediaLabel(String headerFormat) {
        return switch (headerFormat.toUpperCase(Locale.ROOT)) {
            case "IMAGE" -> "image";
            case "VIDEO" -> "vidéo";
            default -> "document";
        };
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
