package com.dev.alex.Service;

import com.dev.alex.Model.FxRate;
import com.dev.alex.Repository.FxRateRepository;
import com.dev.alex.Service.Interface.FxRateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FxRateServiceImpl implements FxRateService {

    @Autowired
    private FxRateRepository fxRateRepository;

    @Override
    public Map<String, BigDecimal> getAllRatesAsMap() {
        return fxRateRepository.findAll().stream()
                .filter(r -> r.getCurrency() != null && r.getRateVsEur() != null)
                .collect(Collectors.toMap(FxRate::getCurrency, FxRate::getRateVsEur));
    }

    @Override
    public BigDecimal getRateForCurrency(String currency) {
        if (currency == null) return BigDecimal.ONE;
        // GBp (pence) = GBP / 100; multiply rate by 100 so frontend division works correctly
        if ("GBp".equals(currency) || "GBx".equals(currency)) {
            return fxRateRepository.findById("GBP")
                    .map(r -> r.getRateVsEur().multiply(BigDecimal.valueOf(100)))
                    .orElse(BigDecimal.valueOf(100));
        }
        return fxRateRepository.findById(currency)
                .map(FxRate::getRateVsEur)
                .orElse(BigDecimal.ONE);
    }

    @Override
    public BigDecimal getRateForCurrency(String currency, Map<String, BigDecimal> rates) {
        if (currency == null || rates == null) return BigDecimal.ONE;
        // GBp (pence) = GBP / 100; multiply rate by 100 so frontend division works correctly
        if ("GBp".equals(currency) || "GBx".equals(currency)) {
            BigDecimal gbp = rates.get("GBP");
            return gbp != null ? gbp.multiply(BigDecimal.valueOf(100)) : BigDecimal.valueOf(100);
        }
        return rates.getOrDefault(currency, BigDecimal.ONE);
    }
}
