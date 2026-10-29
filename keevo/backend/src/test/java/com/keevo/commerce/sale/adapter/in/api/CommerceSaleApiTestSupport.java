package com.keevo.commerce.sale.adapter.in.api;

import com.keevo.commerce.api.DayClosureCommandPort;
import com.keevo.commerce.api.DayClosureQueryPort;
import com.keevo.commerce.api.SaleCommandPort;
import com.keevo.commerce.api.SaleQueryPort;
import com.keevo.commerce.api.SaleView;
import com.keevo.commerce.sale.domain.model.Sale;
import com.keevo.commerce.sale.domain.port.in.CancelPendingSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.CloseDayUseCase;
import com.keevo.commerce.sale.domain.port.in.RecordSaleUseCase;
import com.keevo.commerce.sale.domain.port.in.ValidateSaleUseCase;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.commerce.sale.domain.port.out.SaleRepository;

/**
 * Test-only access to the package-private {@code commerce :: api} adapters (Story 12.2c), for
 * tests of other packages that drive the real adapters over mocked use cases / repositories.
 */
public final class CommerceSaleApiTestSupport {

    private CommerceSaleApiTestSupport() {}

    /** The real sale command adapter; unused delegates may be null. */
    public static SaleCommandPort saleCommandPort(RecordSaleUseCase record, ValidateSaleUseCase validate,
                                                  CancelPendingSaleUseCase cancel) {
        return new SaleCommandAdapter(record, validate, cancel);
    }

    public static SaleQueryPort saleQueryPort(SaleRepository saleRepository) {
        return new SaleQueryAdapter(saleRepository);
    }

    public static DayClosureCommandPort dayClosureCommandPort(CloseDayUseCase closeDayUseCase) {
        return new DayClosureApiAdapter(closeDayUseCase, null);
    }

    public static DayClosureQueryPort dayClosureQueryPort(DayClosureRepository dayClosureRepository) {
        return new DayClosureApiAdapter(null, dayClosureRepository);
    }

    /** The published view of a sale aggregate, mapped exactly as the adapters do. */
    public static SaleView view(Sale sale) {
        return SaleViews.of(sale);
    }
}
