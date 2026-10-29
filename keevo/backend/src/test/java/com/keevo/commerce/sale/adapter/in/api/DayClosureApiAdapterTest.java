package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.CloseDayInput;
import com.keevo.commerce.api.DayClosureSummary;
import com.keevo.commerce.api.DayClosureView;
import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase.CloseDayCommand;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DayClosureApiAdapterTest {

    private final CloseDayUseCase closeDay = mock(CloseDayUseCase.class);
    private final DayClosureRepository repository = mock(DayClosureRepository.class);
    private final DayClosureApiAdapter adapter = new DayClosureApiAdapter(closeDay, repository);

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant CLOSED_AT = Instant.parse("2026-10-29T19:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-10-29T19:00:03Z");
    private static final DayClosureSummary SUMMARY = new DayClosureSummary(3, 12000, null, "Pagne", 4, 7000, 5000, 1, 2000);

    private static DayClosure closure(UUID id, Instant receivedAt) {
        return new DayClosure(id, STORE, ACTOR, CLOSED_AT, SUMMARY, true, "kv_test", receivedAt);
    }

    @Test
    void closeDay_passesEveryFieldAndReturnsTheView() {
        UUID closureId = UUID.randomUUID();
        when(closeDay.closeDay(any())).thenReturn(closure(closureId, RECEIVED_AT));

        DayClosureView view = adapter.closeDay(new CloseDayInput(STORE, ACTOR, "kv_test", false, closureId, CLOSED_AT));

        verify(closeDay).closeDay(new CloseDayCommand(STORE, ACTOR, "kv_test", false, closureId, CLOSED_AT));
        assertThat(view).isEqualTo(new DayClosureView(closureId, STORE, ACTOR, CLOSED_AT, RECEIVED_AT, true, SUMMARY));
    }

    @Test
    void closeDay_alreadyClosed_propagatesTheDomainError() {
        when(closeDay.closeDay(any())).thenThrow(new DomainException(ErrorCode.DAY_ALREADY_CLOSED, "closed"));

        assertThatThrownBy(() -> adapter.closeDay(new CloseDayInput(STORE, ACTOR, "kv_test", false, null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.DAY_ALREADY_CLOSED);
    }

    @Test
    void closeDayInput_withoutStore_isAValidationError() {
        assertThatThrownBy(() -> new CloseDayInput(null, ACTOR, "kv_test", false, null, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void queries_mapTheClosures_legacyReceivedAtFallsBackToClosedAt() {
        UUID id = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 29);
        when(repository.findById(id)).thenReturn(Optional.of(closure(id, null)));
        when(repository.findByStoreIdAndDate(STORE, day)).thenReturn(List.of(closure(id, RECEIVED_AT)));
        when(repository.findPreviousForStore(STORE, CLOSED_AT)).thenReturn(Optional.empty());

        assertThat(adapter.findById(id)).map(DayClosureView::receivedAt).contains(CLOSED_AT);
        assertThat(adapter.findByStoreAndDate(STORE, day))
                .containsExactly(new DayClosureView(id, STORE, ACTOR, CLOSED_AT, RECEIVED_AT, true, SUMMARY));
        assertThat(adapter.findPreviousForStore(STORE, CLOSED_AT)).isEmpty();
    }
}
