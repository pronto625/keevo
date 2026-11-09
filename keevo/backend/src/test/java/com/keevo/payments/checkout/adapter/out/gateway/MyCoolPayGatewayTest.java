package com.keevo.payments.checkout.adapter.out.gateway;

import com.keevo.payments.checkout.domain.model.MobileMoneyMethod;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway.ProviderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("MyCoolPayGateway — Bref's MyCoolPay calls, no real network (Story 21.1)")
class MyCoolPayGatewayTest {

    private static final String BASE = "https://mcp.test";
    private MockRestServiceServer server;
    private MyCoolPayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new MyCoolPayGateway(builder.build(), "PUBKEY", "PRIVKEY");
    }

    private static MobileMoneyGateway.Payin request() {
        return new MobileMoneyGateway.Payin(3500, "XAF", "+237677000111", MobileMoneyMethod.MTN_MOMO, "KV-1",
                "Abonnement Pro — Mensuel");
    }

    @Test
    @DisplayName("payin: Bref's body; success with ussd → PENDING + ussd; REQUIRE_OTP → REQUIRE_OTP")
    void payin() {
        server.expect(requestTo(BASE + "/api/PUBKEY/payin")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transaction_amount").value(3500))
                .andExpect(jsonPath("$.transaction_currency").value("XAF"))
                .andExpect(jsonPath("$.app_transaction_ref").value("KV-1"))
                .andExpect(jsonPath("$.customer_phone_number").value("+237677000111"))
                .andExpect(jsonPath("$.customer_lang").value("fr"))
                .andRespond(withSuccess("{\"status\":\"success\",\"transaction_ref\":\"MCP-1\",\"ussd\":\"*126#\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/PUBKEY/payin"))
                .andRespond(withSuccess("{\"status\":\"success\",\"transaction_ref\":\"MCP-2\","
                        + "\"action\":\"REQUIRE_OTP\"}", MediaType.APPLICATION_JSON));

        var usd = gateway.initiate(request());
        assertThat(usd.providerRef()).isEqualTo("MCP-1");
        assertThat(usd.status()).isEqualTo(ProviderStatus.PENDING);
        assertThat(usd.ussdCode()).isEqualTo("*126#");
        var otp = gateway.initiate(request());
        assertThat(otp.status()).isEqualTo(ProviderStatus.REQUIRE_OTP);
        server.verify();
    }

    @Test
    @DisplayName("payin refused (HTTP 400 or status ≠ success) → GatewayException without key nor URL in the message")
    void payinRefused() {
        server.expect(requestTo(BASE + "/api/PUBKEY/payin"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"message\":\"Numéro invalide\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/PUBKEY/payin"))
                .andRespond(withSuccess("{\"status\":\"error\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.initiate(request())).isInstanceOf(MobileMoneyGateway.GatewayException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("PUBKEY").doesNotContain("PRIVKEY"));
        assertThatThrownBy(() -> gateway.initiate(request())).isInstanceOf(MobileMoneyGateway.GatewayException.class);
    }

    @Test
    @DisplayName("authorize: 401 = wrong code (not accepted), 200 = accepted, 500 = GatewayException")
    void authorize() {
        server.expect(requestTo(BASE + "/api/PUBKEY/payin/authorize"))
                .andExpect(jsonPath("$.transaction_ref").value("MCP-2")).andExpect(jsonPath("$.code").value("1234"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"Code OTP invalide\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/PUBKEY/payin/authorize"))
                .andRespond(withSuccess("{\"status\":\"success\",\"ussd\":\"#150*50#\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/PUBKEY/payin/authorize"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(gateway.authorize("MCP-2", "1234").accepted()).isFalse();
        var ok = gateway.authorize("MCP-2", "1234");
        assertThat(ok.accepted()).isTrue();
        assertThat(ok.ussdCode()).isEqualTo("#150*50#");
        assertThatThrownBy(() -> gateway.authorize("MCP-2", "1234"))
                .isInstanceOf(MobileMoneyGateway.GatewayException.class);
    }

    @Test
    @DisplayName("checkStatus: provider statuses mapped as in Bref (unknown → PENDING), amount and currency")
    void status() {
        server.expect(requestTo(BASE + "/api/PUBKEY/checkStatus/MCP-1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"success\",\"transaction_ref\":\"MCP-1\","
                        + "\"transaction_status\":\"SUCCESS\",\"transaction_amount\":3500,"
                        + "\"transaction_currency\":\"XAF\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/PUBKEY/checkStatus/MCP-1"))
                .andRespond(withSuccess("{\"transaction_status\":\"INITIATED\"}", MediaType.APPLICATION_JSON));
        var paid = gateway.status("MCP-1");
        assertThat(paid.status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(paid.amount()).isEqualTo(3500L);
        assertThat(paid.currency()).isEqualTo("XAF");
        assertThat(gateway.status("MCP-1").status()).isEqualTo(ProviderStatus.PENDING);
        assertThat(MyCoolPayGateway.map("CANCELED")).isEqualTo(ProviderStatus.CANCELED);
        assertThat(MyCoolPayGateway.map("FAILED")).isEqualTo(ProviderStatus.FAILED);
    }

    @Test
    @DisplayName("callback: valid signature with the private key → valid + status; otherwise invalid")
    void callback() {
        Map<String, Object> p = new HashMap<>(Map.of("transaction_ref", "MCP-1", "transaction_type", "PAYIN",
                "transaction_amount", 3500, "transaction_currency", "XAF", "transaction_operator", "CM_OM",
                "transaction_status", "SUCCESS"));
        p.put("signature", MyCoolPaySignature.sign(p, "PRIVKEY"));
        var ok = gateway.verifyCallback(p);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.providerRef()).isEqualTo("MCP-1");
        assertThat(ok.status()).isEqualTo(ProviderStatus.SUCCESS);
        p.put("signature", MyCoolPaySignature.sign(p, "PUBKEY"));
        assertThat(gateway.verifyCallback(p).valid()).isFalse();
        assertThat(MyCoolPayGateway.mask("+237677000111")).isEqualTo("+2376****0111");
    }
}
