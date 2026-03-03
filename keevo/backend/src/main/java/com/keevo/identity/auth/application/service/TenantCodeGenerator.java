package com.keevo.identity.auth.application.service;

import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * TenantCodeGenerator — Generates unique tenant codes in format KV-XXXXXX.
 *
 * <p>GoF Pattern: Strategy — algorithm for code generation is encapsulated here,
 * enabling replacement without modifying TenantFactory.
 * Uses SecureRandom for cryptographically secure randomness.
 */
@Component
public class TenantCodeGenerator {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 10;

    private final TenantRepository tenantRepository;
    private final SecureRandom random;

    public TenantCodeGenerator(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
        this.random = new SecureRandom();
    }

    /**
     * Generate a globally unique tenant code.
     *
     * @return code in format "KV-XXXXXX" (6 uppercase alphanumeric characters)
     * @throws DomainException TENANT_PROVISION_FAILED if uniqueness cannot be guaranteed
     */
    public String generate() {
        int attempts = 0;
        String code;
        do {
            if (attempts >= MAX_ATTEMPTS) {
                throw new DomainException(ErrorCode.TENANT_PROVISION_FAILED,
                    "Could not generate unique tenant code after " + MAX_ATTEMPTS + " attempts");
            }
            code = "KV-" + generateSuffix();
            attempts++;
        } while (tenantRepository.existsByCode(code));
        return code;
    }

    private String generateSuffix() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
