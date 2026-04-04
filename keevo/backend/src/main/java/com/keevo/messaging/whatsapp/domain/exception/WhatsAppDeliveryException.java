package com.keevo.messaging.whatsapp.domain.exception;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

public class WhatsAppDeliveryException extends DomainException {

    public WhatsAppDeliveryException(String message) {
        super(ErrorCode.WHATSAPP_DELIVERY_FAILED, message);
    }

    public WhatsAppDeliveryException(String message, Throwable cause) {
        super(ErrorCode.WHATSAPP_DELIVERY_FAILED, cause);
    }
}
