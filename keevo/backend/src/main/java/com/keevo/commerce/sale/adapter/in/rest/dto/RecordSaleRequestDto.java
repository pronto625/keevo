package com.keevo.commerce.sale.adapter.in.rest.dto;

import com.keevo.commerce.sale.domain.model.PaymentMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record RecordSaleRequestDto(
        @NotNull UUID saleId,
        @NotNull PaymentMode paymentMode,
        UUID clientId,
        String mobileMoneyRef,
        UUID storeId,
        @NotEmpty @Valid List<SaleItemRequestDto> items
) {}
