package com.keevo.payments.checkout.adapter.out.gateway;

import com.keevo.payments.checkout.domain.model.PaymentProvider;
import com.keevo.payments.checkout.domain.port.out.MobileMoneyGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Story 21.1 — MyCoolPay (real money), ported from Bref ({@code providers/mycoolpay/mycoolpay.adapter.ts}):
 * {@code POST /api/{publicKey}/payin}, {@code POST /api/{publicKey}/payin/authorize} (HTTP 401 = wrong code),
 * {@code GET /api/{publicKey}/checkStatus/{ref}}, callback signature {@link MyCoolPaySignature}. The public key is in
 * the URL: URLs, keys and provider bodies are never logged nor put in an exception message; phones are masked.
 */
public class MyCoolPayGateway implements MobileMoneyGateway {

    private static final Logger log = LoggerFactory.getLogger(MyCoolPayGateway.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final String publicKey;
    private final String privateKey;

    /** @param http client whose base URL is the MyCoolPay host, with connect / read timeouts */
    public MyCoolPayGateway(RestClient http, String publicKey, String privateKey) {
        this.http = http;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.MYCOOLPAY;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public PayinResult initiate(Payin payin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_amount", payin.amountXaf());
        body.put("transaction_currency", payin.currency());
        body.put("transaction_reason", payin.reason());
        body.put("app_transaction_ref", payin.appTransactionRef());
        body.put("customer_phone_number", payin.phone());
        body.put("customer_lang", "fr");
        log.info("MyCoolPay payin: amount={} phone={} method={}", payin.amountXaf(), mask(payin.phone()),
                payin.method());
        Map<String, Object> data = call("payin", () -> http.post().uri("/api/{key}/payin", publicKey)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> ok(res.getStatusCode(), "payin") ? res.bodyTo(JSON) : null));
        if (data == null || !"success".equals(str(data.get("status"))) || str(data.get("transaction_ref")) == null) {
            throw new GatewayException("MyCoolPay payin: unexpected answer");
        }
        String ref = str(data.get("transaction_ref"));
        if ("REQUIRE_OTP".equals(str(data.get("action")))) {
            return new PayinResult(ref, ProviderStatus.REQUIRE_OTP, null);
        }
        return new PayinResult(ref, ProviderStatus.PENDING, str(data.get("ussd")));
    }

    @Override
    public AuthorizeResult authorize(String providerRef, String code) {
        Map<String, Object> body = Map.of("transaction_ref", providerRef, "code", code);
        return call("authorize", () -> http.post().uri("/api/{key}/payin/authorize", publicKey)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().value() == 401) {
                        return new AuthorizeResult(false, null);
                    }
                    if (!ok(res.getStatusCode(), "authorize")) {
                        throw new GatewayException("MyCoolPay authorize: HTTP " + res.getStatusCode().value());
                    }
                    Map<String, Object> data = res.bodyTo(JSON);
                    return new AuthorizeResult(true, data == null ? null : str(data.get("ussd")));
                }));
    }

    @Override
    public StatusResult status(String providerRef) {
        Map<String, Object> data = call("checkStatus", () -> http.get()
                .uri("/api/{key}/checkStatus/{ref}", publicKey, providerRef).accept(MediaType.APPLICATION_JSON)
                .exchange((req, res) -> ok(res.getStatusCode(), "checkStatus") ? res.bodyTo(JSON) : null));
        if (data == null) {
            throw new GatewayException("MyCoolPay checkStatus: empty answer");
        }
        return new StatusResult(map(str(data.get("transaction_status"))), amount(data.get("transaction_amount")),
                str(data.get("transaction_currency")));
    }

    @Override
    public CallbackResult verifyCallback(Map<String, Object> payload) {
        if (!MyCoolPaySignature.verify(payload, privateKey)) {
            return CallbackResult.INVALID;
        }
        return new CallbackResult(true, str(payload.get("transaction_ref")),
                map(str(payload.get("transaction_status"))), str(payload.get("app_transaction_ref")));
    }

    /** Provider statuses as Bref maps them: anything else than SUCCESS / FAILED / CANCELED is still pending. */
    static ProviderStatus map(String status) {
        if (status == null) {
            return ProviderStatus.PENDING;
        }
        return switch (status) {
            case "SUCCESS" -> ProviderStatus.SUCCESS;
            case "FAILED" -> ProviderStatus.FAILED;
            case "CANCELED", "CANCELLED" -> ProviderStatus.CANCELED;
            default -> ProviderStatus.PENDING;
        };
    }

    private static boolean ok(HttpStatusCode status, String operation) {
        if (status.is2xxSuccessful()) {
            return true;
        }
        throw new GatewayException("MyCoolPay " + operation + ": HTTP " + status.value());
    }

    private static <T> T call(String operation, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (GatewayException e) {
            log.warn("{}", e.getMessage());
            throw e;
        } catch (RestClientException | IllegalStateException e) {
            log.warn("MyCoolPay {} failed: {}", operation, e.getClass().getSimpleName());
            throw new GatewayException("MyCoolPay " + operation + " failed", e);
        }
    }

    /** An amount of the provider (number or numeric string), rounded to the franc; {@code null} when absent. */
    static Long amount(Object v) {
        if (v instanceof Number n) {
            return Math.round(n.doubleValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Math.round(Double.parseDouble(s.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** {@code +2376****1111}: country prefix and the last 4 digits. */
    static String mask(String phone) {
        if (phone == null || phone.length() < 8) {
            return "***";
        }
        return phone.substring(0, 5) + "****" + phone.substring(phone.length() - 4);
    }
}
