package com.keevo.messaging.channel.adapter.out.external;

import com.keevo.messaging.channel.adapter.out.config.EmailChannelProperties;
import com.keevo.messaging.channel.adapter.out.config.ProviderKeys;
import com.keevo.shared.capability.notification.EmailAttachment;
import com.keevo.shared.capability.notification.EmailMessage;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("Email provider adapters — HTTP contract (Story 19.9)")
class EmailProvidersHttpTest {

    private static final String SENDER = "\"Bref Point\" <noreply@brefpoint.app>";
    private static final byte[] PDF = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
    private static final String PDF_B64 = Base64.getEncoder().encodeToString(PDF);

    private RestTemplate http;
    private MockRestServiceServer server;
    private EmailChannelProperties properties;

    @BeforeEach
    void setUp() {
        http = new RestTemplate();
        server = MockRestServiceServer.bindTo(http).build();
        properties = new EmailChannelProperties(List.of("resend", "infobip"), "noreply@brefpoint.app", "Bref Point",
                "re_secret", new ProviderKeys("xyz.api.infobip.com", "ib_secret"));
    }

    private static EmailMessage message() {
        return new EmailMessage("awa@example.cm", "Votre facture", "Bonjour", "<p>Bonjour</p>", null,
                List.of(new EmailAttachment("facture.pdf", "application/pdf", PDF)));
    }

    @Test
    @DisplayName("Resend: POST api.resend.com/emails, Bearer, {from, to:[to], subject, text, html, attachments}")
    void resendRequest() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_secret"))
                .andExpect(content().json("{\"from\":\"\\\"Bref Point\\\" <noreply@brefpoint.app>\","
                        + "\"to\":[\"awa@example.cm\"],\"subject\":\"Votre facture\",\"text\":\"Bonjour\","
                        + "\"html\":\"<p>Bonjour</p>\",\"attachments\":[{\"filename\":\"facture.pdf\","
                        + "\"content\":\"" + PDF_B64 + "\"}]}", true))
                .andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThat(new ResendEmailProvider(http, properties).send(message(), SENDER)).isEqualTo(SendOutcome.DELIVERED);
        server.verify();
    }

    @Test
    @DisplayName("Resend: no html / attachments keys when absent; 5xx → not delivered")
    void resendMinimalAndFailure() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(content().json("{\"from\":\"noreply@x.cm\",\"to\":[\"a@b.cm\"],\"subject\":\"S\","
                        + "\"text\":\"T\"}", true))
                .andRespond(withServerError());

        assertThat(new ResendEmailProvider(http, properties).send(EmailMessage.of("a@b.cm", "S", "T"),
                "noreply@x.cm")).isEqualTo(SendOutcome.FAILED);
        server.verify();
    }

    @Test
    @DisplayName("Infobip: POST https://{base}/email/4/messages, App auth, sender/destinations/content/attachments")
    void infobipRequest() {
        server.expect(requestTo("https://xyz.api.infobip.com/email/4/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "App ib_secret"))
                .andExpect(content().json("{\"messages\":[{\"sender\":\"\\\"Bref Point\\\" <noreply@brefpoint.app>\","
                        + "\"destinations\":[{\"to\":[{\"destination\":\"awa@example.cm\"}]}],"
                        + "\"content\":{\"subject\":\"Votre facture\",\"text\":\"Bonjour\",\"html\":\"<p>Bonjour</p>\"},"
                        + "\"attachments\":[{\"data\":\"" + PDF_B64 + "\",\"fileName\":\"facture.pdf\","
                        + "\"contentType\":\"application/pdf\"}]}]}", true))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(new InfobipEmailProvider(http, properties).send(message(), SENDER)).isEqualTo(SendOutcome.DELIVERED);
        server.verify();
    }

    @Test
    @DisplayName("Infobip 400 → not delivered")
    void infobipFailure() {
        server.expect(requestTo("https://xyz.api.infobip.com/email/4/messages"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(new InfobipEmailProvider(http, properties).send(message(), SENDER)).isEqualTo(SendOutcome.FAILED);
    }

    @Test
    @DisplayName("isConfigured: Resend needs its key, Infobip base URL and key")
    void configured() {
        EmailChannelProperties none = new EmailChannelProperties(List.of(), "", "", "",
                new ProviderKeys("", "ib_secret"));
        assertThat(new ResendEmailProvider(http, properties).isConfigured()).isTrue();
        assertThat(new ResendEmailProvider(http, none).isConfigured()).isFalse();
        assertThat(new InfobipEmailProvider(http, none).isConfigured()).isFalse();
    }
}
