package com.keevo.admin.analytics.domain.port.in;

import com.keevo.admin.analytics.domain.model.CountryCount;

import java.util.List;

public interface GetGeographyUseCase {
    List<CountryCount> getGeography();
}
