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
                .collect(Collectors.toMap(FxRate::getCurrency, FxRate::getRateVsEur));
    }

    @Override
    public BigDecimal getRateForCurrency(String currency) {
        if (currency == null) return BigDecimal.ONE;
        return fxRateRepository.findById(currency)
                .map(FxRate::getRateVsEur)
                .orElse(BigDecimal.ONE);
    }
}
