package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /api/v1/invoices/{id}/send} — Story 14.2: {@code phone} optional (the client's phone when absent).
 * Story 20.8 (additive): {@code channel} = {@code WHATSAPP} (default when absent or blank), {@code SMS} or
 * {@code EMAIL} (case-insensitive), or Bref's {@code channels} (several, each processed independently; both may be
 * given, merged without duplicates); {@code email} optional (the client's email when absent), only with the EMAIL
 * channel; {@code phone} is the WhatsApp / SMS number, refused when EMAIL is the only channel. Also the payload of the
 * offline {@code SEND_INVOICE} sync operation (with {@code invoiceId}).
 *
 * <p>Note: the over-long number message reads « Le numéro de téléphone est trop long » since 20.8 (14.2: « Le numéro
 * WhatsApp est trop long »), the number also being an SMS one.
 */
public record SendInvoiceRequestDto(
        @Size(max = 30, message = "Le numéro de téléphone est trop long") String phone,
        @Pattern(regexp = CHANNEL, message = UNKNOWN_CHANNEL) String channel,
        @Size(max = 3, message = UNKNOWN_CHANNEL) List<@Pattern(regexp = CHANNEL, message = UNKNOWN_CHANNEL) String> channels,
        @Size(max = 255, message = "L'adresse e-mail est trop longue") String email) {

    /** A channel name, case-insensitive; blank = WhatsApp. */
    static final String CHANNEL = "(?i)\\s*(WHATSAPP|SMS|EMAIL)?\\s*";
    static final String UNKNOWN_CHANNEL = "Canal d'envoi inconnu : choisissez WhatsApp, SMS ou e-mail";

    /** Bref's {@code channels} given (the answer then lists one result per channel). */
    public boolean multiChannel() {
        return channels != null;
    }

    /**
     * The use case request (absent body = WhatsApp to the client's phone).
     *
     * @throws DomainException {@code VALIDATION_ERROR} (422, French) — no channel in {@code channels}, an address
     *         without the EMAIL channel, a number with EMAIL as the only channel
     */
    public static SendInvoiceUseCase.SendRequest toRequest(SendInvoiceRequestDto dto) {
        if (dto == null) {
            return SendInvoiceUseCase.SendRequest.whatsApp(null);
        }
        List<SendChannel> chosen = new ArrayList<>();
        if (dto.channel() != null && !dto.channel().isBlank()) {
            chosen.add(SendChannel.parse(dto.channel()));
        }
        if (dto.channels() != null) {
            if (dto.channels().isEmpty() && chosen.isEmpty()) {
                throw invalid("Choisissez au moins un canal d'envoi");
            }
            for (String c : dto.channels()) {
                chosen.add(SendChannel.parse(c));
            }
        }
        SendInvoiceUseCase.SendRequest request = new SendInvoiceUseCase.SendRequest(chosen, dto.phone(), dto.email());
        boolean email = request.channels().contains(SendChannel.EMAIL);
        if (dto.email() != null && !dto.email().isBlank() && !email) {
            throw invalid("L'adresse e-mail ne sert qu'à l'envoi par e-mail");
        }
        if (dto.phone() != null && !dto.phone().isBlank() && email && request.channels().size() == 1) {
            throw invalid("Le numéro de téléphone ne sert pas à l'envoi par e-mail");
        }
        return request;
    }

    private static DomainException invalid(String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message);
    }
}
