package com.keevo.admin.catalog.domain.port.in;

import com.keevo.admin.catalog.domain.model.AdminProductListItem;
import org.springframework.data.domain.Page;

public interface ListProductsUseCase {
    Page<AdminProductListItem> execute(ListProductsQuery query);
}
