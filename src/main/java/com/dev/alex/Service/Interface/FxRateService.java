package com.dev.alex.Service.Interface;

import java.math.BigDecimal;
import java.util.Map;

public interface FxRateService {
    Map<String, BigDecimal> getAllRatesAsMap();
    BigDecimal getRateForCurrency(String currency);
}
