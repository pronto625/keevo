package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.CancelSaleInput;
import com.keevo.commerce.api.RecordSaleInput;
import com.keevo.commerce.api.SaleLineInput;
import com.keevo.commerce.api.SalePaymentMode;
import com.keevo.commerce.api.SaleState;
import com.keevo.commerce.api.ValidateSaleInput;
import com.keevo.commerce.sale.domain.model.PaymentMode;
import com.keevo.commerce.sale.domain.model.SaleStatus;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase.CancelPendingSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.RecordSaleCommand;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase.SaleItemCommand;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase.ValidateSaleCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SaleCommandAdapterTest {

    private final RecordSaleUseCase record = mock(RecordSaleUseCase.class);
    private final ValidateSaleUseCase validate = mock(ValidateSaleUseCase.class);
    private final CancelPendingSaleUseCase cancel = mock(CancelPendingSaleUseCase.class);
    private SaleCommandAdapter adapter;

    private static final UUID SALE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new SaleCommandAdapter(record, validate, cancel);
    }

    @Test
    void recordSale_passesEveryFieldAndLineToTheUseCase() {
        UUID client = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-29T10:00:00Z");

        adapter.recordSale(new RecordSaleInput(SALE, ACTOR, STORE, client, SalePaymentMode.MOBILE_MONEY, "MM-42",
                500, SaleState.PENDING_VALIDATION,
                List.of(new SaleLineInput(item, product, variant, "Pagne", 3000, 2500, 2)), occurredAt, true));

        var captor = ArgumentCaptor.forClass(RecordSaleCommand.class);
        verify(record).recordSale(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new RecordSaleCommand(SALE, ACTOR, STORE, client,
                PaymentMode.MOBILE_MONEY, "MM-42", 500, SaleStatus.PENDING_VALIDATION,
                List.of(new SaleItemCommand(item, product, variant, "Pagne", 3000, 2500, 2)), occurredAt, true));
    }

    @Test
    void recordSale_nullRequestedStatus_staysNull() {
        adapter.recordSale(new RecordSaleInput(SALE, ACTOR, STORE, null, SalePaymentMode.CASH, null, 0, null,
                List.of(), null, false));

        var captor = ArgumentCaptor.forClass(RecordSaleCommand.class);
        verify(record).recordSale(captor.capture());
        assertThat(captor.getValue().requestedStatus()).isNull();
        assertThat(captor.getValue().paymentMode()).isEqualTo(PaymentMode.CASH);
    }

    @Test
    void recordSale_insufficientStock_propagatesTheDomainError() {
        doThrow(new DomainException(ErrorCode.INSUFFICIENT_STOCK, "short")).when(record).recordSale(any());

        assertThatThrownBy(() -> adapter.recordSale(new RecordSaleInput(SALE, ACTOR, STORE, null,
                SalePaymentMode.CASH, null, 0, null, List.of(), null, false)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void validateSale_passesEveryFieldToTheUseCase() {
        UUID assigned = UUID.randomUUID();
        Map<UUID, UUID> remap = Map.of(UUID.randomUUID(), UUID.randomUUID());
        Map<UUID, Integer> initial = Map.of(UUID.randomUUID(), 3);

        adapter.validateSale(new ValidateSaleInput(SALE, ACTOR, assigned, "justification", remap, initial));

        verify(validate).validateSale(new ValidateSaleCommand(SALE, ACTOR, assigned, "justification", remap, initial));
    }

    @Test
    void cancelSale_passesEveryFieldToTheUseCase() {
        UUID assigned = UUID.randomUUID();

        adapter.cancelSale(new CancelSaleInput(SALE, ACTOR, assigned, "erreur de saisie", true));

        verify(cancel).cancelPendingSale(new CancelPendingSaleCommand(SALE, ACTOR, assigned, "erreur de saisie", true));
    }

    @ParameterizedTest
    @EnumSource(SalePaymentMode.class)
    void everyPaymentMode_mapsToTheInternalModeOfTheSameName(SalePaymentMode mode) {
        assertThat(SaleViews.toPaymentMode(mode).name()).isEqualTo(mode.name());
    }

    @ParameterizedTest
    @EnumSource(SaleState.class)
    void everySaleState_mapsToTheInternalStatusOfTheSameName(SaleState state) {
        assertThat(SaleViews.toSaleStatus(state).name()).isEqualTo(state.name());
    }

    @Test
    void apiEnums_haveExactlyTheInternalConstants() {
        assertThat(Arrays.stream(SalePaymentMode.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(PaymentMode.values()).map(Enum::name).toList());
        assertThat(Arrays.stream(SaleState.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(SaleStatus.values()).map(Enum::name).toList());
    }

    @Test
    void inputs_missingRequiredFields_areValidationErrors() {
        assertValidationError(() -> new RecordSaleInput(null, ACTOR, STORE, null, SalePaymentMode.CASH, null, 0,
                null, List.of(), null, false));
        assertValidationError(() -> new RecordSaleInput(SALE, ACTOR, null, null, SalePaymentMode.CASH, null, 0,
                null, List.of(), null, false));
        assertValidationError(() -> new RecordSaleInput(SALE, ACTOR, STORE, null, null, null, 0,
                null, List.of(), null, false));
        assertValidationError(() -> new RecordSaleInput(SALE, ACTOR, STORE, null, SalePaymentMode.CASH, null, 0,
                null, null, null, false));
        assertValidationError(() -> new SaleLineInput(null, null, null, "x", 1, 1, 1));
        assertValidationError(() -> new ValidateSaleInput(null, ACTOR, null, null, null, null));
        assertValidationError(() -> new CancelSaleInput(null, ACTOR, null, null, false));
    }

    private static void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
