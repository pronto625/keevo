package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.ClientPortalCodePort;
import com.keevo.identity.auth.application.service.OtpRateLimiter;
import com.keevo.identity.auth.application.service.OtpSettings;
import com.keevo.identity.auth.application.service.RequestOtpService;
import com.keevo.identity.auth.application.service.VerifyOtpService;
import com.keevo.identity.auth.domain.model.OtpIdentifier;
import com.keevo.identity.auth.domain.model.OtpPurpose;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Implements {@link ClientPortalCodePort} (Story 21.7) over the 19.2 code core: {@link RequestOtpService#issue} and
 * {@link VerifyOtpService#check} with {@link OtpPurpose#CLIENT_PORTAL}. Same limits, same channels (SMS + WhatsApp for
 * a phone), same review account; no Keevo account is read or created.
 */
@Component
class ClientPortalCodeAdapter implements ClientPortalCodePort {

    private final OtpSettings settings;
    private final OtpRateLimiter rateLimiter;
    private final RequestOtpService requests;
    private final VerifyOtpService checks;
    private final Clock clock;

    ClientPortalCodeAdapter(OtpSettings settings, OtpRateLimiter rateLimiter, RequestOtpService requests,
                            VerifyOtpService checks, Clock clock) {
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.requests = requests;
        this.checks = checks;
        this.clock = clock;
    }

    @Override
    public int requestCode(String phone, String clientIp, String language) {
        OtpIdentifier identifier = phone(phone);
        return requests.issue(identifier, identifier.channelsFor(null), OtpPurpose.CLIENT_PORTAL, clientIp, language);
    }

    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public String verifyCode(String phone, String code, String clientIp) {
        rateLimiter.acquireVerify(clientIp);
        OtpIdentifier identifier = phone(phone);
        checks.check(identifier, code, OtpPurpose.CLIENT_PORTAL, clock.instant());
        return identifier.value();
    }

    /**
     * A phone of an allowed country, normalized exactly like the client phone of an invoice
     * ({@link PhoneNumbers#toE164} with {@code +237}: {@code 677 00 01 11} → {@code +237677000111}) so the portal
     * finds what the merchants typed; an email (or anything else) is refused.
     */
    private OtpIdentifier phone(String raw) {
        if (raw == null || raw.indexOf('@') >= 0) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "portal codes go to a phone");
        }
        String e164 = PhoneNumbers.toE164(raw, PhoneNumbers.CAMEROON);
        return OtpIdentifier.parse(e164 != null ? e164 : raw, settings.allowedPhonePrefixes());
    }
}
