package com.keevo.messaging.channel.adapter.out.external;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.keevo.messaging.channel.adapter.out.config.ProviderKeys;
import com.keevo.messaging.channel.adapter.out.config.SmsChannelProperties;
import org.junit.jupiter.api.AfterEach;
import com.keevo.messaging.channel.domain.model.SendOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("SMS provider adapters — HTTP contract (Story 19.9)")
class SmsProvidersHttpTest {

    private static final String MBOA_KEY = "mboa-secret-key";
    private static final String INFOBIP_KEY = "infobip-secret-key";

    private RestTemplate http;
    private MockRestServiceServer server;
    private SmsChannelProperties properties;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        http = new RestTemplate();
        server = MockRestServiceServer.bindTo(http).build();
        properties = new SmsChannelProperties(List.of("mboasms", "infobip"), "BrefPoint",
                new ProviderKeys("https://api.mboasms.test/", MBOA_KEY),
                new ProviderKeys("xyz.api.infobip.com", INFOBIP_KEY));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ProviderHttp.class)).addAppender(logs);
        ((Logger) LoggerFactory.getLogger(InfobipSmsProvider.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ProviderHttp.class)).detachAppender(logs);
        ((Logger) LoggerFactory.getLogger(InfobipSmsProvider.class)).detachAppender(logs);
    }

    @Test
    @DisplayName("MboaSMS: POST /api/v1/developer/sms/send, X-API-Key, {phoneNumbers, message, senderId}; 2xx = delivered")
    void mboasmsRequest() {
        server.expect(requestTo("https://api.mboasms.test/api/v1/developer/sms/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", MBOA_KEY))
                .andExpect(content().json("{\"phoneNumbers\":[\"+237699000123\"],\"message\":\"Bonjour\","
                        + "\"senderId\":\"BrefPoint\"}", true))
                .andRespond(withSuccess("{\"successCount\":1}", MediaType.APPLICATION_JSON));

        assertThat(new MboaSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint")).isEqualTo(SendOutcome.DELIVERED);
        server.verify();
    }

    @Test
    @DisplayName("MboaSMS 503 → not delivered, WARN with provider and status only")
    void mboasmsFailure() {
        server.expect(requestTo("https://api.mboasms.test/api/v1/developer/sms/send"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("down for +237699000123"));

        assertThat(new MboaSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint")).isEqualTo(SendOutcome.FAILED);
        assertThat(warnings()).containsExactly("[SMS] mboasms failed (HTTP 503)");
    }

    @Test
    @DisplayName("Infobip: POST https://{base}/sms/2/text/advanced, App auth, {messages:[{from, destinations, text}]}")
    void infobipRequest() {
        server.expect(requestTo("https://xyz.api.infobip.com/sms/2/text/advanced"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "App " + INFOBIP_KEY))
                .andExpect(content().json("{\"messages\":[{\"from\":\"BrefPoint\",\"destinations\":"
                        + "[{\"to\":\"+237699000123\"}],\"text\":\"Bonjour\"}]}", true))
                .andRespond(withSuccess("{\"messages\":[{\"status\":{\"groupName\":\"PENDING\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(new InfobipSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint")).isEqualTo(SendOutcome.DELIVERED);
        server.verify();
    }

    @Test
    @DisplayName("Infobip 200 with a REJECTED status group → not delivered")
    void infobipRejected() {
        server.expect(requestTo("https://xyz.api.infobip.com/sms/2/text/advanced"))
                .andRespond(withSuccess("{\"messages\":[{\"status\":{\"groupName\":\"REJECTED\","
                        + "\"name\":\"REJECTED_DESTINATION\"}}]}", MediaType.APPLICATION_JSON));

        assertThat(new InfobipSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint")).isEqualTo(SendOutcome.FAILED);
        assertThat(warnings()).containsExactly("[SMS] infobip failed (REJECTED REJECTED_DESTINATION)");
    }

    @Test
    @DisplayName("Infobip 401 → not delivered, the key never logged")
    void infobipUnauthorized() {
        server.expect(requestTo("https://xyz.api.infobip.com/sms/2/text/advanced"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(new InfobipSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint")).isEqualTo(SendOutcome.FAILED);
        assertThat(warnings()).containsExactly("[SMS] infobip failed (HTTP 401)")
                .noneMatch(m -> m.contains(INFOBIP_KEY));
    }

    @Test
    @DisplayName("2xx with a non-JSON body → accepted (no failover, no double send), REJECTED check skipped")
    void nonJsonAccepted() {
        server.expect(requestTo("https://api.mboasms.test/api/v1/developer/sms/send"))
                .andRespond(withSuccess("OK", MediaType.TEXT_PLAIN));
        server.expect(requestTo("https://xyz.api.infobip.com/sms/2/text/advanced"))
                .andRespond(withSuccess("<html>accepted</html>", MediaType.TEXT_HTML));

        assertThat(new MboaSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint"))
                .isEqualTo(SendOutcome.DELIVERED);
        assertThat(new InfobipSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint"))
                .isEqualTo(SendOutcome.DELIVERED);
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("read timeout after the request went out → UNKNOWN (the provider may have accepted it)")
    void readTimeoutUnknown() {
        server.expect(requestTo("https://api.mboasms.test/api/v1/developer/sms/send"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        assertThat(new MboaSmsProvider(http, properties).send("+237699000123", "Bonjour", "BrefPoint"))
                .isEqualTo(SendOutcome.UNKNOWN);
    }

    @Test
    @DisplayName("connect failure or connect timeout → FAILED (nothing sent: the chain may fail over)")
    void connectFailureFailed() {
        server.expect(requestTo("https://api.mboasms.test/api/v1/developer/sms/send"))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });
        server.expect(requestTo("https://api.mboasms.test/api/v1/developer/sms/send"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Connect timed out");
                });

        MboaSmsProvider provider = new MboaSmsProvider(http, properties);
        assertThat(provider.send("+237699000123", "Bonjour", "BrefPoint")).isEqualTo(SendOutcome.FAILED);
        assertThat(provider.send("+237699000123", "Bonjour", "BrefPoint")).isEqualTo(SendOutcome.FAILED);
    }

    @Test
    @DisplayName("isConfigured needs both base URL and key")
    void configured() {
        assertThat(new MboaSmsProvider(http, properties).isConfigured()).isTrue();
        SmsChannelProperties noKey = new SmsChannelProperties(List.of(), "BrefPoint",
                new ProviderKeys("https://api.mboasms.test", ""), new ProviderKeys("", "k"));
        assertThat(new MboaSmsProvider(http, noKey).isConfigured()).isFalse();
        assertThat(new InfobipSmsProvider(http, noKey).isConfigured()).isFalse();
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
