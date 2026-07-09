package com.dev.alex.Service.Interface;

import java.math.BigDecimal;
import java.util.Map;

public interface FxRateService {
    Map<String, BigDecimal> getAllRatesAsMap();
    BigDecimal getRateForCurrency(String currency);
    // Same as getRateForCurrency but resolves against a pre-fetched rates map (no DB hit),
    // for callers that convert many rows in a loop.
    BigDecimal getRateForCurrency(String currency, Map<String, BigDecimal> rates);
}
