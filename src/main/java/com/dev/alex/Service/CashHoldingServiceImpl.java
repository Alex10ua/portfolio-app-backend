package com.dev.alex.Service;

import com.dev.alex.Model.CashHolding;
import com.dev.alex.Repository.CashHoldingRepository;
import com.dev.alex.Service.Interface.CashHoldingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class CashHoldingServiceImpl implements CashHoldingService {

    @Autowired
    private CashHoldingRepository cashHoldingRepository;

    @Override
    public List<CashHolding> getCashHoldings(String portfolioId) {
        return cashHoldingRepository.findAllByPortfolioId(portfolioId);
    }

    @Override
    public CashHolding upsertCashHolding(String portfolioId, String currency, BigDecimal amount) {
        String normalized = normalizeCurrency(currency);
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        CashHolding holding = cashHoldingRepository
                .findByPortfolioIdAndCurrency(portfolioId, normalized)
                .orElseGet(() -> {
                    CashHolding created = new CashHolding();
                    created.setPortfolioId(portfolioId);
                    created.setCurrency(normalized);
                    return created;
                });
        holding.setAmount(amount);
        holding.setUpdatedAt(LocalDate.now());
        return cashHoldingRepository.save(holding);
    }

    @Override
    public void deleteCashHolding(String portfolioId, String currency) {
        cashHoldingRepository.deleteByPortfolioIdAndCurrency(portfolioId, normalizeCurrency(currency));
    }

    /** ISO-4217 style: exactly 3 letters, stored uppercase. */
    private String normalizeCurrency(String currency) {
        if (currency == null || !currency.trim().matches("(?i)[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be a 3-letter code, got: " + currency);
        }
        return currency.trim().toUpperCase();
    }
}
